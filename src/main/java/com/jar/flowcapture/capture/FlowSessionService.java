package com.jar.flowcapture.capture;

import org.springframework.stereotype.Service;

/**
 * Holds the single in-progress recording session. v1 is intentionally single-session
 * (one QA engineer, one flow, one dashboard instance at a time) — matches how the tool
 * is meant to be run locally per engineer, not as a shared multi-user service.
 *
 * Every mutation is persisted to disk (FlowPersistenceService) so a crashed process
 * or a paused recording can resume instead of losing the whole session.
 */
@Service
public class FlowSessionService {

    private final FlowPersistenceService persistenceService;
    private volatile RecordedFlow currentFlow;

    public FlowSessionService(FlowPersistenceService persistenceService) {
        this.persistenceService = persistenceService;
        this.currentFlow = persistenceService.loadIfPresent();
    }

    public synchronized RecordedFlow startNewFlow(String flowName) {
        currentFlow = new RecordedFlow();
        currentFlow.setFlowName(flowName);
        persistenceService.save(currentFlow);
        return currentFlow;
    }

    public RecordedFlow requireCurrentFlow() {
        if (currentFlow == null) {
            throw new IllegalStateException("No recording in progress — start a flow first");
        }
        return currentFlow;
    }

    /** Call after any mutation to the current flow's steps (add/remove/update), so the on-disk copy never falls behind. */
    public void persistCurrentState() {
        if (currentFlow != null) {
            persistenceService.save(currentFlow);
        }
    }

    public boolean hasActiveFlow() {
        return currentFlow != null;
    }

    public synchronized void endFlow() {
        currentFlow = null;
        persistenceService.clear();
    }
}
