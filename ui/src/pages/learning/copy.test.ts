import { describe, expect, it } from 'vitest';
import { authorName, personName } from './copy';

const learners = new Map([['node_reliability', 'Node reliability']]);

describe('actor names', () => {
  it('names system actors in plain words', () => {
    expect(authorName('system:template-author', learners)).toBe('the change drafter (template, no AI)');
    expect(authorName('system:author', learners)).toBe('the AI change drafter');
    expect(personName('system:guardrail-breach-monitor')).toBe('the guardrail monitor');
  });

  it('marks seeded actors as demo data, with the same names', () => {
    expect(authorName('system:demo-seed.template-author', learners)).toBe('the change drafter (template, no AI) (demo)');
    expect(authorName('system:demo-seed.node_reliability', learners)).toBe('Node reliability (demo)');
    expect(authorName('system:demo-seed.strategy', learners)).toBe('strategy lead (demo)');
    expect(personName('system:demo-seed.admin')).toBe('admin (demo)');
    expect(personName('system:demo-seed')).toBe('demo data');
  });

  it('keeps people and learners as they are', () => {
    expect(authorName('user:alice', learners)).toBe('alice');
    expect(authorName('system:node_reliability', learners)).toBe('Node reliability');
    expect(personName('user:bob')).toBe('bob');
  });
});
