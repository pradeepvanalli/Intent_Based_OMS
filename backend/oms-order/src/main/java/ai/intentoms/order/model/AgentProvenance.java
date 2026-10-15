package ai.intentoms.order.model;

import ai.intentoms.finance.model.Money;

import java.util.Map;

/** Evidence recorded on every agent-initiated order (FR-CAP-07): identity, protocol, mandate, limits, instruction text. */
public record AgentProvenance(String agentId, String protocol /*ucp|acp|mcp*/, String mandateId, Money spendLimit, String instructionText,
                              String signature, String buyerRef, Boolean withinMandate, String mandateDecisionRecordId, Map<String, Object> raw) {}
