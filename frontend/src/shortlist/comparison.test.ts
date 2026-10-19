import { expect, it } from 'vitest';
import { availabilityBadge } from './comparison';

it.each([
  ['a', null, 'Available', 'available'],
  ['a', undefined, 'Available', 'available'],
  ['a', 100, 'Available · 100%', 'available'],
  ['a', 50, 'Available · 50%', 'unknown'],
  ['a', 0, 'Available · 0%', 'unknown'],
  ['d', 75, 'Doubtful · 75%', 'yellow'],
  ['d', 50, 'Doubtful · 50%', 'amber'],
  ['d', 25, 'Doubtful · 25%', 'amber'],
  ['d', 0, 'Doubtful · 0%', 'red'],
  ['d', null, 'Doubtful', 'unknown'],
  ['d', undefined, 'Doubtful', 'unknown'],
  ['d', 100, 'Doubtful · 100%', 'unknown'],
  ['d', 60, 'Doubtful · 60%', 'unknown'],
  ['i', 75, 'Injured · 75%', 'red'],
  ['s', null, 'Suspended', 'red'],
  ['u', null, 'Unavailable', 'red'],
  ['n', null, 'Not available', 'red'],
  [null, 75, 'Unknown · 75%', 'unknown'],
  ['x', 75, 'Unknown · 75%', 'unknown'],
  [null, 0, 'Unknown · 0%', 'red'],
  ['d', -1, 'Doubtful', 'unknown'],
  ['d', 101, 'Doubtful', 'unknown'],
  ['d', 25.5, 'Doubtful', 'unknown'],
  ['d', NaN, 'Doubtful', 'unknown'],
])(
  'maps captured status %s / chance %s without inventing a reading',
  (status, chance, label, flag) => {
    expect(availabilityBadge(status, chance)).toEqual({ label, flag });
  },
);
