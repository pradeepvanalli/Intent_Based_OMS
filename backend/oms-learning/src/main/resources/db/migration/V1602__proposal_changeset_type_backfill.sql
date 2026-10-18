-- Proposals written before the typed-writer fix stored each PolicyChange without its "type" discriminator, so they
-- could not be read back. Infer the variant from the fields each record type carries and add the discriminator.
update proposal p
set changeset = fixed.changeset
from (
  select pr.tenant_id, pr.proposal_id,
         jsonb_agg(
           case
             when jsonb_exists(e.elem, 'type') then e.elem
             when jsonb_exists(e.elem, 'band') then e.elem || '{"type":"ThresholdChange"}'::jsonb
             when jsonb_exists(e.elem, 'weightKey') then e.elem || '{"type":"WeightChange"}'::jsonb
             when jsonb_exists(e.elem, 'expression') then e.elem || '{"type":"RuleAdd"}'::jsonb
             when jsonb_exists(e.elem, 'ruleId') then e.elem || '{"type":"RuleRetire"}'::jsonb
             when jsonb_exists(e.elem, 'listName') then e.elem || '{"type":"AllowListChange"}'::jsonb
             when jsonb_exists(e.elem, 'parameter') then e.elem || '{"type":"ParameterChange"}'::jsonb
             else e.elem
           end order by e.ord) as changeset
  from proposal pr
  cross join lateral jsonb_array_elements(pr.changeset) with ordinality as e(elem, ord)
  where jsonb_typeof(pr.changeset) = 'array'
  group by pr.tenant_id, pr.proposal_id
) fixed
where p.tenant_id = fixed.tenant_id and p.proposal_id = fixed.proposal_id
  and exists (select 1 from jsonb_array_elements(p.changeset) x where not jsonb_exists(x, 'type'));
