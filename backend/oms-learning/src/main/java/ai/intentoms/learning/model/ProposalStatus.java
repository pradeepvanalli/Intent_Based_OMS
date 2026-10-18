package ai.intentoms.learning.model;

/** proposal.status lifecycle (requirements doc §9.1): draft -&gt; evaluated -&gt; approved -&gt; canary -&gt;
 * promoted | rejected | rolled_back. */
public enum ProposalStatus { DRAFT, EVALUATED, APPROVED, CANARY, PROMOTED, REJECTED, ROLLED_BACK }
