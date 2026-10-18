-- One episode per decision point (requirements doc §4: "one decision point plus everything that followed it"), not
-- per evaluation. A scheduled scorer re-evaluated every open order every minute and opened an episode each time
-- (3,000+ episodes for ~150 orders), which drove outcome coverage to ~2%. The first evaluation is the anchor; later
-- evaluations of the same point update last_decision_id and evaluation_count.
alter table episode add column if not exists subject_type text;
alter table episode add column if not exists subject_id text;
alter table episode add column if not exists last_decision_id text;
alter table episode add column if not exists evaluation_count int not null default 1;
alter table episode add column if not exists decision_point_key text;
alter table episode add column if not exists reward_computed_at timestamptz;

update episode e
set subject_type = r.subject_type, subject_id = r.subject_id
from decision_record r
where r.tenant_id = e.tenant_id and r.id = e.decision_id and e.subject_type is null;

update episode
set decision_point_key = decision_name || '|' || coalesce(subject_type, '') || '|' || coalesce(subject_id, order_id, '')
                         || '|' || coalesce(line_id, '') || '|' || coalesce(fulfillment_group_id, ''),
    last_decision_id = coalesce(last_decision_id, decision_id)
where decision_point_key is null;

-- Merge duplicates into the oldest episode of each decision point.
create temporary table episode_merge on commit drop as
select tenant_id, episode_id, decision_id, decision_point_key,
       first_value(episode_id) over w as anchor_id,
       last_value(decision_id) over (partition by tenant_id, decision_point_key order by created_at, episode_id
                                     rows between unbounded preceding and unbounded following) as latest_decision,
       count(*) over (partition by tenant_id, decision_point_key) as n,
       row_number() over w as rn
from episode
window w as (partition by tenant_id, decision_point_key order by created_at, episode_id);

update outcome o set episode_id = m.anchor_id
from episode_merge m
where o.tenant_id = m.tenant_id and o.episode_id = m.episode_id and m.rn > 1;

delete from outcome o using outcome o2
where o.tenant_id = o2.tenant_id and o.episode_id = o2.episode_id and o.source_event_id = o2.source_event_id
  and o.source_event_id is not null and o.outcome_id > o2.outcome_id;

update episode e set last_decision_id = m.latest_decision, evaluation_count = m.n
from episode_merge m
where e.tenant_id = m.tenant_id and e.episode_id = m.episode_id and m.rn = 1 and m.n > 1;

delete from episode e using episode_merge m
where e.tenant_id = m.tenant_id and e.episode_id = m.episode_id and m.rn > 1;

alter table episode alter column decision_point_key set not null;
create unique index if not exists episode_decision_point_uq on episode(tenant_id, decision_point_key);
create index if not exists episode_tenant_subject_idx on episode(tenant_id, subject_type, subject_id);
create unique index if not exists outcome_episode_event_uq on outcome(tenant_id, episode_id, source_event_id) where source_event_id is not null;
