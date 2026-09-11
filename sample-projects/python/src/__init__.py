"""
CHRONOVAULT demo — zero-dependency FizzBuzz and factorial for demonstration.
"""


def fizzbuzz(n: int) -> str:
    if n % 15 == 0:
        return "fizzbuzz"
    if n % 3 == 0:
        return "fizz"
    if n % 5 == 0:
        return "buzz"
    return str(n)


def factorial(n: int) -> int:
    if n < 0:
        raise ValueError("n must be non-negative")
    result = 1
    for i in range(2, n + 1):
        result *= i
    return result


def main() -> None:
    for i in range(1, 21):
        print(f"{i}: {fizzbuzz(i)}")


if __name__ == "__main__":
    main()