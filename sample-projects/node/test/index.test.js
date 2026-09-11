"use strict";

const { test } = require("node:test");
const assert = require("node:assert/strict");
const { isPalindrome, summary } = require("../src/index.js");

test("detects palindromes", () => {
  assert.equal(isPalindrome("racecar"), true);
  assert.equal(isPalindrome("level"), true);
  assert.equal(isPalindrome("hello"), false);
});

test("summarizes a list", () => {
  const s = summary(["racecar", "hello", "rotator"]);
  assert.equal(s.total, 3);
  assert.equal(s.palindromes, 2);
});