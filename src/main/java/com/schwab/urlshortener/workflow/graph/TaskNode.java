package com.schwab.urlshortener.workflow.graph;

import java.util.List;

/**
 * One node of a workflow graph. {@code agent} names a registered
 * {@link com.schwab.urlshortener.workflow.engine.StageAgent}, which supplies
 * the node's stage, the artifacts it consumes and produces, and its entry and
 * exit gates. {@code dependsOn} lists the node keys that must SUCCEED before this
 * one may start. {@code requiresApproval} pauses the task in WAITING_APPROVAL after
 * its exit gate passes, until a human decides.
 */
public record TaskNode(String key, String agent, List<String> dependsOn, boolean requiresApproval) {

    public TaskNode {
        dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
    }
}
