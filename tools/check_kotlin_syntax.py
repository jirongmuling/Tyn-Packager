#!/usr/bin/env python3
"""Kotlin 源文件基础语法自检：注释配平、括号配平、字符串未闭合。

只做粗粒度检查，用于在推 CI 前发现明显的语法错误。
"""
import sys, os, re

def check(path):
    src = open(path, encoding='utf-8').read()
    i, n = 0, len(src)
    depth_block = 0      # 块注释嵌套深度
    in_line = False
    in_str = False
    in_char = False
    in_raw = False       # 三引号字符串
    line = 1
    stack = []           # 括号栈
    errors = []

    while i < n:
        ch = src[i]
        nxt = src[i + 1] if i + 1 < n else ''

        if ch == '\n':
            line += 1
            in_line = False
            i += 1
            continue

        if in_line:
            i += 1
            continue

        if in_raw:
            if src.startswith('"""', i):
                in_raw = False
                i += 3
                continue
            i += 1
            continue

        if depth_block > 0:
            if src.startswith('/*', i):
                depth_block += 1
                i += 2
                continue
            if src.startswith('*/', i):
                depth_block -= 1
                i += 2
                continue
            i += 1
            continue

        if in_str:
            if ch == '\\':
                i += 2
                continue
            if ch == '"':
                in_str = False
            i += 1
            continue

        if in_char:
            if ch == '\\':
                i += 2
                continue
            if ch == "'":
                in_char = False
            i += 1
            continue

        # 正常代码
        if src.startswith('//', i):
            in_line = True
            i += 2
            continue
        if src.startswith('/*', i):
            depth_block += 1
            i += 2
            continue
        if src.startswith('"""', i):
            in_raw = True
            i += 3
            continue
        if ch == '"':
            in_str = True
            i += 1
            continue
        if ch == "'":
            in_char = True
            i += 1
            continue
        if ch in '({[':
            stack.append((ch, line))
        elif ch in ')}]':
            pairs = {')': '(', '}': '{', ']': '['}
            if not stack:
                errors.append(f"第 {line} 行：多余的 '{ch}'")
            else:
                op, ol = stack.pop()
                if op != pairs[ch]:
                    errors.append(f"第 {line} 行：'{ch}' 与第 {ol} 行的 '{op}' 不匹配")
        i += 1

    if depth_block > 0:
        errors.append(f"文件结束时块注释未闭合（剩余深度 {depth_block}）")
    if in_str:
        errors.append("文件结束时字符串未闭合")
    if stack:
        op, ol = stack[-1]
        errors.append(f"文件结束时仍有未闭合的 '{op}'（第 {ol} 行）")
    return errors

def main():
    root = sys.argv[1] if len(sys.argv) > 1 else 'app/src/main/java'
    bad = 0
    for dirpath, _, files in os.walk(root):
        for f in files:
            if not f.endswith('.kt'):
                continue
            p = os.path.join(dirpath, f)
            errs = check(p)
            if errs:
                bad += 1
                print(f"[FAIL] {p}")
                for e in errs:
                    print(f"    {e}")
    print(f"\n检查完成：{'全部通过' if bad == 0 else f'{bad} 个文件有问题'}")
    return 1 if bad else 0

if __name__ == '__main__':
    sys.exit(main())
