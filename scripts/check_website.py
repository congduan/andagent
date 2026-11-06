#!/usr/bin/env python3
"""Validate local website links/assets and basic HTML structure without dependencies."""
from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import unquote, urlsplit
import re

ROOT = Path(__file__).resolve().parents[1] / 'website'
VOID = {'area', 'base', 'br', 'col', 'embed', 'hr', 'img', 'input', 'link', 'meta', 'param', 'source', 'track', 'wbr'}

class Check(HTMLParser):
    def __init__(self, path):
        super().__init__(convert_charrefs=True)
        self.path = path
        self.stack = []
        self.ids = set()
        self.references = []
        self.errors = []

    def handle_starttag(self, tag, attrs):
        data = dict(attrs)
        if tag not in VOID:
            self.stack.append(tag)
        if 'id' in data:
            if data['id'] in self.ids:
                self.errors.append(f'duplicate id: {data["id"]}')
            self.ids.add(data['id'])
        for attr in ('href', 'src'):
            if data.get(attr):
                self.references.append((tag, attr, data[attr]))
        if tag in ('script', 'link', 'img', 'source'):
            for attr in ('src', 'href'):
                value = data.get(attr, '')
                if value and urlsplit(value).scheme:
                    self.errors.append(f'external resource: {tag} {value}')

    def handle_endtag(self, tag):
        if tag in VOID:
            self.errors.append(f'void element closed: {tag}')
        elif not self.stack or self.stack[-1] != tag:
            self.errors.append(f'mismatched close: {tag}')
        else:
            self.stack.pop()

    def finish(self):
        self.close()
        if self.stack:
            self.errors.append('unclosed tags: ' + ', '.join(self.stack))
        for tag, attr, value in self.references:
            parsed = urlsplit(value)
            if parsed.scheme or value.startswith('//'):
                if tag != 'a' or parsed.scheme != 'https' or parsed.netloc != 'github.com':
                    self.errors.append(f'disallowed external link: {value}')
                continue
            if parsed.path.startswith('/'):
                self.errors.append(f'absolute local path: {value}')
                continue
            target = (self.path.parent / unquote(parsed.path)).resolve() if parsed.path else self.path
            if not target.is_relative_to(ROOT.resolve()) or not target.is_file():
                self.errors.append(f'missing local file: {value}')
            elif parsed.fragment and target.suffix == '.html':
                if target == self.path and parsed.fragment not in self.ids:
                    self.errors.append(f'missing local anchor: {value}')
                elif target != self.path and f'id="{parsed.fragment}"' not in target.read_text():
                    self.errors.append(f'missing target anchor: {value}')
        return self.errors

errors = []
PAGES = ('index.html', 'guide.html', 'tools.html', 'playbook.html', 'faq.html', '404.html')
for name in PAGES:
    path = ROOT / name
    check = Check(path)
    check.feed(path.read_text(encoding='utf-8'))
    found = check.finish()
    print(f'{path.relative_to(ROOT)}: {"PASS" if not found else "FAIL"} ({len(check.references)} href/src)')
    errors.extend(f'{path.name}: {error}' for error in found)
for error in errors:
    print(error)
if errors:
    raise SystemExit(1)
print('All HTML structures, links, anchors and local assets passed.')
