import { describe, expect, it } from 'vitest';
import { escalationChoices } from './escalation';

describe('escalationChoices', () => {
  it('reads the {key: description} options the escalation handler sends (this used to crash the Tasks page)', () => {
    expect(escalationChoices({ accept: 'Act on the recommendation', override: 'Choose another value (details.value) with a reason' }))
      .toEqual([['accept', 'Act on the recommendation'], ['override', 'Choose another value (details.value) with a reason']]);
  });
  it('still accepts a plain list from older tasks', () => {
    expect(escalationChoices(['accept_proposed', 'other']).map(([k]) => k)).toEqual(['accept_proposed', 'other']);
  });
  it('falls back to accept / override when the task carries none', () => {
    expect(escalationChoices(undefined).map(([k]) => k)).toEqual(['accept', 'override']);
  });
});
