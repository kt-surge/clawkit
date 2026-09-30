package com.clawkit.ops.loop.managed;

import java.util.List;

/** Read-only Agent view and a lease preventing knowledge revocation racing an authorized repair. */
public interface KnowledgeAccess {
    default boolean available(ManagedApplication app) throws Exception { return true; }
    OpsKnowledge.SearchResult search(ManagedApplication app,String query,int limit,List<DecisionEvidence> evidence) throws Exception;
    void validate(ManagedApplication app,List<OpsKnowledge.Reference> references) throws Exception;
    default void validateProposal(ManagedApplication app,List<OpsKnowledge.Reference> references,OpsDecision decision,List<DecisionEvidence> evidence) throws Exception {
        validate(app,references);
    }
    default List<ManagedObserver.Probe> proposalProbes(ManagedApplication app,List<OpsKnowledge.Reference> references) throws Exception { return List.of(); }
    AutoCloseable guard(ManagedApplication app,List<OpsKnowledge.Reference> references) throws Exception;
    static KnowledgeAccess none() {
        return new KnowledgeAccess() {
            public boolean available(ManagedApplication a) { return false; }
            public OpsKnowledge.SearchResult search(ManagedApplication a,String q,int k,List<DecisionEvidence> e) { return OpsKnowledge.SearchResult.empty(); }
            public void validate(ManagedApplication a,List<OpsKnowledge.Reference> refs) { if(!refs.isEmpty()) throw new IllegalArgumentException("knowledge unavailable"); }
            public AutoCloseable guard(ManagedApplication a,List<OpsKnowledge.Reference> refs) { validate(a,refs); return () -> {}; }
        };
    }
}
