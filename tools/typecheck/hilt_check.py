#!/usr/bin/env python3
"""
Checks the Hilt dependency graph without running the Hilt processor: every parameter of every @Inject constructor
must be a type that has an @Inject constructor itself, is returned by an @Provides method, or is supplied by Hilt
(@ApplicationContext Context, SavedStateHandle in ViewModels, @Assisted worker parameters). Also checks that every
@HiltViewModel has an @Inject constructor and that @Singleton-scoped classes do not depend on ViewModel-only types.
Usage: python3 tools/typecheck/hilt_check.py
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SRC = os.path.join(ROOT, "app/src/main/kotlin")
BUILT_IN = {"SavedStateHandle"}


def balanced(text, start):
    depth = 0
    for i in range(start, len(text)):
        if text[i] == "(":
            depth += 1
        elif text[i] == ")":
            depth -= 1
            if depth == 0:
                return i
    raise ValueError


def main():
    injectable, provided, consumers, problems = set(), set(), [], []
    for dirpath, _, files in os.walk(SRC):
        for f in files:
            if not f.endswith(".kt"):
                continue
            path = os.path.join(dirpath, f)
            src = open(path).read()
            for m in re.finditer(r"class (\w+)(?:<[^>]*>)? @(?:Inject|AssistedInject) constructor\(", src):
                name = m.group(1)
                injectable.add(name)
                body = src[m.end():balanced(src, m.end() - 1)]
                params = re.findall(r"((?:@\w+\s+)*)(?:private |protected |internal )?(?:val |var )?\w+:\s*([\w.]+)", body)
                consumers.append((name, os.path.relpath(path, ROOT), params, src[: m.start()].rstrip().endswith("@HiltViewModel") or "@HiltViewModel\nclass " + name in src))
            for m in re.finditer(r"@Provides(?:\s+@\w+)*\s+fun \w+\([^)]*\)\s*:\s*([\w.]+)", src):
                provided.add(m.group(1).split(".")[-1])
            for m in re.finditer(r"@HiltViewModel\s+class (\w+)(?:<[^>]*>)?\s*@Inject constructor", src):
                pass
            for m in re.finditer(r"@HiltViewModel\s+class (\w+)\s*(?!@Inject)", src):
                tail = src[m.end():m.end() + 40]
                if not tail.lstrip().startswith("@Inject constructor"):
                    problems.append(f"{m.group(1)}: @HiltViewModel without @Inject constructor")
    for name, path, params, is_vm in consumers:
        for ann, t in params:
            t = t.split(".")[-1]
            if "@Assisted" in ann:
                continue
            if t == "Context" and "@ApplicationContext" in ann:
                continue
            if t == "Context":
                problems.append(f"{path}: {name} needs @ApplicationContext on its Context parameter")
                continue
            if t in BUILT_IN:
                if not is_vm:
                    problems.append(f"{path}: {name} asks for {t}, which Hilt only provides to ViewModels")
                continue
            if t not in injectable and t not in provided:
                problems.append(f"{path}: {name} needs {t}, which nothing provides")
    for p in problems:
        print("PROBLEM:", p)
    print(f"{len(injectable)} injectable classes, {len(provided)} @Provides, {sum(len(c[2]) for c in consumers)} dependencies checked, {len(problems)} problem(s)")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
