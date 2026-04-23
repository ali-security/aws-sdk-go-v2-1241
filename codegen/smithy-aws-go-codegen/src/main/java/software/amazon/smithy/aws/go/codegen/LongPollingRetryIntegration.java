/*
 * Copyright 2026 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package software.amazon.smithy.aws.go.codegen;

import java.util.List;
import java.util.Map;
import java.util.Set;

import software.amazon.smithy.aws.traits.ServiceTrait;
import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.go.codegen.GoDelegator;
import software.amazon.smithy.go.codegen.GoSettings;
import software.amazon.smithy.go.codegen.GoWriter;
import software.amazon.smithy.go.codegen.SmithyGoDependency;
import software.amazon.smithy.go.codegen.SymbolUtils;
import software.amazon.smithy.go.codegen.integration.ConfigFieldResolver;
import software.amazon.smithy.go.codegen.integration.GoIntegration;
import software.amazon.smithy.go.codegen.integration.RuntimeClientPlugin;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.ServiceShape;
import software.amazon.smithy.model.traits.LongPollTrait;
import software.amazon.smithy.utils.ListUtils;

import static software.amazon.smithy.go.codegen.GoWriter.goTemplate;

/**
 * Configures the retryer for long-polling operations to back off even when the
 * retry quota is exhausted.
 */
public class LongPollingRetryIntegration implements GoIntegration {

    private static final String FINALIZE_LONG_POLLING_RETRYER = "finalizeLongPollingRetryer";

    // Hardcoded long-polling operations until the aws.api#longPoll trait is
    // applied to service models.
    private static final Map<String, Set<String>> LONG_POLLING_OPERATIONS = Map.of(
            "SQS", Set.of("ReceiveMessage"),
            "SFN", Set.of("GetActivityTask"),
            "SWF", Set.of("PollForActivityTask", "PollForDecisionTask")
    );

    private static boolean isLongPollingOperation(Model model, ServiceShape service, OperationShape operation) {
        if (operation.hasTrait(LongPollTrait.class)) {
            return true;
        }
        return service.getTrait(ServiceTrait.class)
                .map(trait -> {
                    Set<String> ops = LONG_POLLING_OPERATIONS.get(trait.getSdkId());
                    return ops != null && ops.contains(operation.getId().getName());
                })
                .orElse(false);
    }

    @Override
    public void writeAdditionalFiles(
            GoSettings settings,
            Model model,
            SymbolProvider symbolProvider,
            GoDelegator delegator
    ) {
        ServiceShape service = settings.getService(model);
        boolean hasLongPollingOps = service.getAllOperations().stream()
                .flatMap(id -> model.getShape(id).stream())
                .filter(shape -> shape.isOperationShape())
                .map(shape -> shape.asOperationShape().get())
                .anyMatch(op -> isLongPollingOperation(model, service, op));

        if (!hasLongPollingOps) {
            return;
        }

        delegator.useShapeWriter(service, this::writeLongPollingResolver);
    }

    private void writeLongPollingResolver(GoWriter writer) {
        writer.write(goTemplate("""
                $os:D $retry:D
                func finalizeLongPollingRetryer(o *Options) {
                    if os.Getenv("AWS_NEW_RETRIES_2026") == "true" {
                        o.Retryer = retry.AddWithLongPolling(o.Retryer)
                    }
                }
                """, Map.of(
                "os", SmithyGoDependency.OS,
                "retry", AwsGoDependency.AWS_RETRY
        )));
    }

    @Override
    public List<RuntimeClientPlugin> getClientPlugins() {
        return ListUtils.of(
                RuntimeClientPlugin.builder()
                        .operationPredicate(LongPollingRetryIntegration::isLongPollingOperation)
                        .addConfigFieldResolver(ConfigFieldResolver.builder()
                                .location(ConfigFieldResolver.Location.OPERATION)
                                .target(ConfigFieldResolver.Target.FINALIZATION)
                                .resolver(SymbolUtils.createValueSymbolBuilder(
                                        FINALIZE_LONG_POLLING_RETRYER).build())
                                .build())
                        .build()
        );
    }
}
