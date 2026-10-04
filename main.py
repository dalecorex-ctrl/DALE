"""My Application1 — application entry point."""


def greet(name: str) -> str:
    return f"Hello, {name}!"


def main() -> None:
    print(greet("My Application1"))


if __name__ == "__main__":
    main()
