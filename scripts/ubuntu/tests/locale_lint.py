#!/usr/bin/env python3
"""Locale rules of the Ubuntu scripts (lint_test.sh, locale_test.sh): python3 locale_lint.py <scripts dir>."""
import os, re, sys
root = sys.argv[1]
entry = ['update.sh', 'install.sh', 'core-update.sh', 'domains-sync.sh', 'preflight.sh', 'harden-host.sh', 'backup.sh']
libraries = ['db-roles.sh', 'nginx-site.sh', 'units.sh', 'dist.sh', 'backup.sh', 'migrations.sh', 'mirror.sh']
locale_line = 'if [[ $(LC_ALL=C.UTF-8 locale charmap 2>/dev/null) == UTF-8 ]]; then export LC_ALL=C.UTF-8; else export LC_ALL=C; fi'
helper = 'bms_ascii_match() { local LC_ALL=C; [[ $1 =~ $2 ]]; }'
rng = re.compile(r'\[[^\]\s]*[A-Za-z0-9!]-[A-Za-z0-9~][^\]\s]*\]|\[\[:[a-z]+:\]\]')
problems = []
for name in sorted(os.listdir(root)):
    if not name.endswith('.sh'):
        continue
    lines = open(os.path.join(root, name), encoding='utf-8').read().split('\n')
    code = [(i + 1, l) for i, l in enumerate(lines) if l.strip() and not l.strip().startswith('#')]
    if name in entry and not any(l.strip() == locale_line for _, l in code):
        problems.append(f'{name}: no fija su locale ({locale_line})')
    uses = any('bms_ascii_match "' in l for _, l in code)
    defines = any(l.strip() == helper for _, l in code)
    if uses and not defines:
        problems.append(f'{name}: usa bms_ascii_match sin definirlo')
    in_heredoc = None
    for number, line in code:
        if in_heredoc:
            if line.strip() == in_heredoc:
                in_heredoc = None
            continue
        heredoc = re.search(r"<<-?'?([A-Z]+)'?\s*(\)|$|[|;&])", line)
        if heredoc and not line.rstrip().endswith("'"):
            in_heredoc = heredoc.group(1)
        if 'locale-lint: unprotected on purpose' in line:
            continue
        if '=~' in line and line.strip() != helper:
            problems.append(f'{name}:{number}: [[ =~ ]] fuera de bms_ascii_match: {line.strip()}')
        if re.search(r'(==|!=)\s*\S*\[[^\]\s]*[A-Za-z0-9]-[A-Za-z0-9][^\]\s]*\]', line) and '[[' in line:
            problems.append(f'{name}:{number}: rango en un patron glob: {line.strip()}')
        if re.match(r'\s*[^#]*\[[^\]]*[A-Za-z0-9]-[A-Za-z0-9][^\]]*\][^)]*\)\s', line) and re.search(r'^\s*[^ ]*\)', line):
            problems.append(f'{name}:{number}: rango en un patron de case: {line.strip()}')
        if name in libraries:
            for match in re.finditer(r'(?:^|[|;&(`]|\$\()\s*((?:LC_ALL=C\s+)?)(sort|grep|sed|awk|tr|find)\b', line):
                protected = bool(match.group(1))
                tool = match.group(2)
                needs = tool == 'sort' or bool(rng.search(line)) or (tool == 'find' and '-name' in line and '[' in line)
                if needs and not protected:
                    problems.append(f'{name}:{number}: {tool} sin LC_ALL=C en una biblioteca: {line.strip()}')
for problem in problems:
    print('    ' + problem, file=sys.stderr)
sys.exit(1 if problems else 0)
