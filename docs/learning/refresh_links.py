"""将学习文档本地链接转换为相对路径，并检查目标存在。"""

import argparse
import os
import re
from pathlib import Path
from urllib.parse import quote, unquote, urlsplit
from urllib.request import url2pathname


LINK = re.compile(r"(?<!!)\[([^\]\n]+)\]\(([^)\n]+)\)")
DRIVE_PATH = re.compile(r"^[A-Za-z]:[/\\]")


def encode_path(path):
    # 保留中文可读性，编码会影响 Markdown 链接解析的字符。
    for source, target in (("%", "%25"), (" ", "%20"), ("(", "%28"),
                           (")", "%29"), ("#", "%23"), ("?", "%3F")):
        path = path.replace(source, target)
    return path


def rewrite_document(document, check_only=False):
    original = document.read_text(encoding="utf-8")
    local_count = 0

    def replace(match):
        nonlocal local_count
        label, target = match.groups()
        target = target.strip().removeprefix("<").removesuffix(">")
        if target.startswith("#"):
            return match.group(0)

        if DRIVE_PATH.match(target):
            path = Path(unquote(target))
            fragment = ""
        else:
            parsed = urlsplit(target)
            if parsed.scheme == "file":
                if parsed.netloc not in ("", "localhost"):
                    raise ValueError(f"不支持的文件 URL 主机：{document.name}")
                path = Path(url2pathname(parsed.path))
            elif parsed.scheme:
                return match.group(0)
            else:
                path = document.parent / unquote(parsed.path)
            fragment = parsed.fragment

        path = path.resolve(strict=True)
        if not path.is_file():
            raise ValueError(f"链接目标不是文件：{path}")
        relative = os.path.relpath(path, document.parent).replace(os.sep, "/")
        href = encode_path(relative)
        if fragment:
            href += "#" + quote(unquote(fragment), safe="-._~")
        local_count += 1
        return f"[{label}]({href})"

    updated = LINK.sub(replace, original)
    changed = updated != original
    if changed and not check_only:
        with document.open("w", encoding="utf-8", newline="\n") as stream:
            stream.write(updated)
    if changed and check_only:
        raise ValueError(f"仍有需要转换的链接：{document.name}")
    return local_count, changed


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("paths", nargs="*", type=Path)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    documents = set()
    for path in args.paths or [Path(__file__).resolve().parent]:
        if path.is_dir():
            documents.update(path.glob("*.md"))
        elif path.is_file() and path.suffix.lower() == ".md":
            documents.add(path)
        else:
            parser.error(f"请输入 Markdown 文件或目录：{path}")

    for document in sorted(documents):
        count, changed = rewrite_document(document.resolve(), args.check)
        print(f"{document.name}: {count} local links checked; updated={changed}")


if __name__ == "__main__":
    main()
