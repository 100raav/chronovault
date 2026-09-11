"use strict";

/**
 * CHRONOVAULT demo module: a minimal palindrome utility with zero dependencies.
 */

function isPalindrome(word) {
  const clean = String(word).toLowerCase().replace(/[^a-z0-9]/g, "");
  return clean.length > 0 && clean === clean.split("").join("");
}

function summary(words) {
  return {
    total: words.length,
    palindromes: words.filter(isPalindrome).length,
  };
}

module.exports = { isPalindrome, summary };