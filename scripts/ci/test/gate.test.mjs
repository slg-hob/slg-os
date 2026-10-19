import assert from 'node:assert/strict';
import { test } from 'node:test';
import { evaluate } from '../gate.mjs';

test('all jobs successful passes', () => {
  assert.deepEqual(evaluate({ a: { result: 'success' }, b: { result: 'success' } }), []);
});

test('a skipped job fails the gate', () => {
  assert.match(evaluate({ a: { result: 'success' }, b: { result: 'skipped' } }).join(), /job "b" finished with "skipped"/);
});

test('failure and cancellation fail the gate', () => {
  assert.equal(evaluate({ a: { result: 'failure' }, b: { result: 'cancelled' } }).length, 2);
});

test('a required job that is missing from needs fails the gate', () => {
  assert.match(evaluate({ a: { result: 'success' } }, ['a', 'security']).join(), /job "security" finished with "missing"/);
});

test('an empty job list fails', () => {
  assert.deepEqual(evaluate({}), ['no jobs to evaluate']);
  assert.deepEqual(evaluate(undefined), ['no jobs to evaluate']);
});
