"""Smoke tests for the chrono-python-demo (stdlib unittest, no deps)."""
import unittest

from src import fizzbuzz, factorial


class FizzBuzzTest(unittest.TestCase):
    def test_normal(self):
        self.assertEqual(fizzbuzz(1), "1")
        self.assertEqual(fizzbuzz(2), "2")

    def test_multiples(self):
        self.assertEqual(fizzbuzz(3), "fizz")
        self.assertEqual(fizzbuzz(5), "buzz")
        self.assertEqual(fizzbuzz(15), "fizzbuzz")


class FactorialTest(unittest.TestCase):
    def test_values(self):
        self.assertEqual(factorial(0), 1)
        self.assertEqual(factorial(5), 120)
        self.assertEqual(factorial(10), 3628800)


if __name__ == "__main__":
    unittest.main()