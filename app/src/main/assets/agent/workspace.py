"""Runtime-owned workspace operations. No model-supplied shell or Python is executed."""
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import shutil
import sys
import uuid
import time

LIMIT = 65536  # whole-file write cap; edits to larger files go through replace
FILE_LIMIT = 8 * 1024 * 1024  # read/search/replace cap per file
OUTPUT_BUDGET = 12000  # stdout is truncated at 16000 UTF-16 units by the terminal bridge
STDOUT_LIMIT = 15000  # final JSON line, in UTF-16 units, with headroom below the bridge cap
PATCH_LIMIT = 60000
SEARCH_SECONDS = 10  # hard deadline: Python re has no match timeout
# O_NOFOLLOW 是 symlink 逃逸硬化：设备端（Linux/Android）恒有；Windows 桌面端跑本文件单测
# 时 os 没有该属性，退化为 0——仅少一层硬化，写路径另有 symlink/组件检查兜底（见 :591）。
_O_NOFOLLOW = getattr(os, 'O_NOFOLLOW', 0)


class Refused(ValueError):
    """A stable error code plus small, non-sensitive details for the caller."""

    def __init__(self, code, **details):
        super().__init__(code)
        self.details = details


def require(condition, code):
    if not condition:
        raise ValueError(code)


def git(root, *args, strip=True):
    env = os.environ.copy()
    for key in list(env):
        if key.startswith('GIT_'):
            del env[key]
    env['GIT_TERMINAL_PROMPT'] = '0'
    p = subprocess.run(['git', '-c', 'core.hooksPath=/dev/null', '-c', 'commit.gpgsign=false',
                        '-c', 'core.fsmonitor=false', '-c', 'diff.external=', '-C', str(root), *args],
                       env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=25)
    require(p.returncode == 0, 'GIT_OPERATION_FAILED')
    require(len(p.stdout) <= 2 * 1024 * 1024, 'WORKSPACE_OUTPUT_TOO_LARGE')
    text = p.stdout.decode('utf-8', errors='replace')
    return text.strip() if strip else text


def project_path(raw):
    p = Path(raw)
    require(p.is_absolute() and len(p.parts) == 3 and p.parts[1] == 'workspace', 'PROJECT_MUST_BE_WORKSPACE_CHILD')
    require(p.name not in ('mounts', 'browser', 'offloads') and not p.is_symlink(), 'INVALID_PROJECT')
    require(p.is_dir() and str(p.resolve()) == str(p), 'INVALID_PROJECT')
    require(git(p, 'rev-parse', '--show-toplevel') == str(p), 'PROJECT_MUST_BE_GIT_ROOT')
    return p


def internal(root, rel):
    p = root / rel
    cur = root
    for part in Path(rel).parts:
        cur = cur / part
        require(not cur.is_symlink(), 'WORKSPACE_SYMLINK_DENIED')
    return p


def record_path(root, task):
    require(re.fullmatch(r'[a-f0-9]{32}', task or '') is not None, 'INVALID_WORKSPACE_ID')
    return internal(root, '.agent/results/' + task + '.json')


def save(root, record):
    p = record_path(root, record['id'])
    p.parent.mkdir(parents=True, exist_ok=True)
    tmp = p.with_suffix('.tmp')
    require(not tmp.is_symlink(), 'WORKSPACE_SYMLINK_DENIED')
    tmp.write_text(json.dumps(record, ensure_ascii=False), encoding='utf-8')
    tmp.replace(p)


def load(root, task):
    p = record_path(root, task)
    require(not p.is_symlink(), 'WORKSPACE_SYMLINK_DENIED')
    require(p.is_file(), 'WORKSPACE_NOT_FOUND')
    try:
        record = json.loads(p.read_text(encoding='utf-8'))
    except (OSError, UnicodeError, ValueError):
        raise ValueError('INVALID_WORKSPACE_RECORD')
    require(isinstance(record, dict) and record.get('id') == task, 'INVALID_WORKSPACE_RECORD')
    state = record.get('state')
    require(isinstance(state, str) and state in ('editing', 'reviewing', 'ready', 'failed', 'merged', 'discarded'),
            'INVALID_WORKSPACE_RECORD')
    return record


def list_authorized(root, workspace_ids):
    """Read only exact, runtime-authorized result filenames.

    A bad authorized entry is reported as unavailable; it never causes a
    directory scan that could expose records outside the runtime allowlist.
    """
    require(isinstance(workspace_ids, list), 'INVALID_WORKSPACE_IDS')
    for task in workspace_ids:
        require(isinstance(task, str) and re.fullmatch(r'[a-f0-9]{32}', task) is not None,
                'INVALID_WORKSPACE_ID')

    records = []
    unavailable = []
    for task in sorted(set(workspace_ids)):
        try:
            records.append(load(root, task))
        except (OSError, UnicodeError, ValueError, TypeError, KeyError):
            # A listed but unusable entry cannot add a workspace. Reporting its
            # ID lets the caller distinguish this from an empty ledger.
            unavailable.append(task)
    return records, unavailable


def tree_path(root, record):
    return internal(root, '.agent/worktrees/' + record['id'])


def safe_file(tree, relative):
    path = Path(relative)
    require(not path.is_absolute() and bool(path.parts) and all(x not in ('..', '.git', '.agent', '.gitattributes', '.gitmodules') for x in path.parts), 'WORKSPACE_PATH_DENIED')
    p = internal(tree, path)
    require(p != tree, 'WORKSPACE_PATH_DENIED')
    if p.exists():
        require(p.is_file() or p.is_dir(), 'WORKSPACE_SPECIAL_FILE_DENIED')
        if p.is_file():
            require(p.stat().st_nlink == 1, 'WORKSPACE_HARDLINK_DENIED')
    return p


LINE = re.compile(r'[^\n]*\n|[^\n]+\Z')


def lines_of(text):
    """Split on LF only and keep endings, so numbering matches every editor and grep."""
    return LINE.findall(text)


def file_text(p):
    """Bounded UTF-8 read of a regular file without following a final symlink."""
    require(p.is_file(), 'FILE_NOT_FOUND')
    size = p.stat().st_size
    if size > FILE_LIMIT:
        raise Refused('FILE_TOO_LARGE', size_bytes=size, max_bytes=FILE_LIMIT)
    fd = os.open(p, os.O_RDONLY | _O_NOFOLLOW)
    with os.fdopen(fd, 'rb') as handle:
        data = handle.read(FILE_LIMIT + 1)
    require(len(data) <= FILE_LIMIT, 'FILE_TOO_LARGE')
    try:
        return data.decode('utf-8')
    except UnicodeDecodeError:
        raise ValueError('FILE_NOT_UTF8')


def units(text):
    """Length as the Kotlin bridge counts it: UTF-16 code units, not code points."""
    return len(text.encode('utf-16-le')) // 2


def escaped(text):
    return units(json.dumps(text, ensure_ascii=False))


class SearchTimeout(Exception):
    pass


def _deadline(*_):
    raise SearchTimeout()


def int_arg(args, key, default, low, high):
    value = args.get(key, default)
    require(type(value) is int and low <= value <= high, 'INVALID_ARGUMENT')
    return value


def read_lines(text, args):
    rows = lines_of(text)
    start = int_arg(args, 'start_line', 1, 1, max(1, len(rows)))
    count = int_arg(args, 'line_count', 400, 1, 2000)
    out, used, end = [], 0, start - 1
    for index in range(start - 1, min(len(rows), start - 1 + count)):
        cost = escaped(rows[index])
        if out and used + cost > OUTPUT_BUDGET:
            break
        if not out and cost > OUTPUT_BUDGET:
            raise Refused('LINE_TOO_LONG', line=index + 1, char_offset=sum(len(r) for r in rows[:index]),
                          hint='use offset/limit character paging for this line')
        out.append(rows[index])
        used += cost
        end = index + 1
    return {'content': ''.join(out), 'start_line': start, 'end_line': end, 'total_lines': len(rows),
            'total_chars': len(text), 'next_line': end + 1 if end < len(rows) else None}


def search(tree, args):
    query = args.get('query')
    require(isinstance(query, str) and 1 <= len(query) <= 500, 'INVALID_QUERY')
    flags = re.IGNORECASE if args.get('ignore_case') is True else 0
    try:
        pattern = re.compile(query if args.get('regex') is True else re.escape(query), flags)
    except re.error:
        raise ValueError('INVALID_REGEX')
    around = int_arg(args, 'context', 1, 0, 3)
    limit = int_arg(args, 'max_results', 50, 1, 100)
    target = args.get('path')
    if target is None:
        names = git(tree, 'ls-files', '--cached', '--others', '--exclude-standard').splitlines()
    else:
        base = safe_file(tree, target)
        require(base.exists(), 'FILE_NOT_FOUND')
        names = [target] if base.is_file() else git(
            tree, 'ls-files', '--cached', '--others', '--exclude-standard', '--', target).splitlines()
    matches, used, scanned, skipped, truncated = [], 0, 0, 0, False
    previous = signal.signal(signal.SIGALRM, _deadline)
    signal.setitimer(signal.ITIMER_REAL, SEARCH_SECONDS)
    try:
        for name in names:
            try:
                rows = lines_of(file_text(safe_file(tree, name)))
            except (OSError, ValueError):
                skipped += 1
                continue
            scanned += 1
            for index, row in enumerate(rows):
                if not pattern.search(row):
                    continue
                item = {'path': name, 'line': index + 1, 'text': row.rstrip('\r\n')[:300]}
                if around:
                    item['before'] = [r.rstrip('\r\n')[:300] for r in rows[max(0, index - around):index]]
                    item['after'] = [r.rstrip('\r\n')[:300] for r in rows[index + 1:index + 1 + around]]
                cost = units(json.dumps(item, ensure_ascii=False))
                if len(matches) >= limit or used + cost > OUTPUT_BUDGET:
                    truncated = True
                    break
                matches.append(item)
                used += cost
            if truncated:
                break
    except SearchTimeout:
        raise Refused('SEARCH_TIMEOUT', seconds=SEARCH_SECONDS, files_scanned=scanned,
                      hint='simplify the pattern (avoid nested quantifiers) or narrow path')
    finally:
        signal.setitimer(signal.ITIMER_REAL, 0)
        signal.signal(signal.SIGALRM, previous)
    return {'matches': matches, 'truncated': truncated, 'files_scanned': scanned, 'files_skipped': skipped}


def replace(p, args):
    old, new = args.get('old_text'), args.get('new_text')
    require(isinstance(old, str) and old and isinstance(new, str), 'INVALID_REPLACEMENT')
    if len(old) + len(new) > PATCH_LIMIT:
        raise Refused('PATCH_TOO_LARGE', max_chars=PATCH_LIMIT, hint='split into smaller replace calls')
    expected = int_arg(args, 'expected_count', 1, 1, 1000)
    text = file_text(p)
    found = text.count(old)
    adapted = False
    if found == 0 and '\r\n' in text and '\r' not in old:
        # Models often normalize CRLF away; adapt both sides so the file keeps its own endings.
        crlf_old = old.replace('\n', '\r\n')
        if crlf_old != old and text.count(crlf_old):
            # Normalize first so a new_text that already uses CRLF does not become CR CR LF.
            crlf_new = new.replace('\r\n', '\n').replace('\n', '\r\n')
            old, new, found, adapted = crlf_old, crlf_new, text.count(crlf_old), True
    if found == 0:
        raise Refused('OLD_TEXT_NOT_FOUND', matches=0, hint='search or read the exact current text first')
    if found != expected:
        raise Refused('MATCH_COUNT_MISMATCH', matches=found, expected=expected,
                      hint='add surrounding lines to make old_text unique, or set expected_count')
    data = text.replace(old, new).encode('utf-8')
    if len(data) > FILE_LIMIT:
        raise Refused('FILE_TOO_LARGE', size_bytes=len(data), max_bytes=FILE_LIMIT)
    mode = p.stat().st_mode & 0o7777
    tmp = p.parent / ('.' + p.name + '.' + uuid.uuid4().hex + '.tmp')
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_EXCL | _O_NOFOLLOW, 0o600)
    try:
        with os.fdopen(fd, 'wb') as out:
            out.write(data)
            out.flush()
            os.fsync(out.fileno())
        os.chmod(tmp, mode)
        require(not p.is_symlink(), 'WORKSPACE_SYMLINK_DENIED')
        os.replace(tmp, p)
    finally:
        if tmp.exists():
            tmp.unlink()
    return {'changed': args['path'], 'replacements': found, 'line_endings_adapted': adapted,
            'size_bytes': len(data)}


def clean(root):
    # .agent is runtime-owned, even before a user's ignore rules have been configured.
    return not git(root, 'status', '--porcelain', '--untracked-files=all', '--', '.', ':(exclude).agent')


def valid_commit(root, value):
    """Only runtime-recorded full object IDs may authorize integration, never ref expressions."""
    if not isinstance(value, str) or re.fullmatch(r'(?:[a-f0-9]{40}|[a-f0-9]{64})', value) is None:
        return False
    try:
        return git(root, 'rev-parse', '--verify', value + '^{commit}') == value
    except ValueError:
        return False


def workspace_tree_exists(root, record):
    tree = tree_path(root, record)
    if not tree.is_dir() or not (tree / '.git').is_file() or (tree / '.git').is_symlink():
        return False
    try:
        # A missing .git must not make Git silently walk up to the parent project.
        # Windows 上 git 输出正斜杠 + 展开后的长路径名，与 str(tree)（可能含反斜杠/8.3
        # 短名）直接比必不等；两边都 resolve 成真实路径再比（POSIX 上同为 realpath，
        # 设备端语义不变）。
        return Path(git(tree, 'rev-parse', '--show-toplevel')).resolve() == tree.resolve()
    except ValueError:
        return False


def implementation_evidence(root, record):
    """Recomputed Git evidence, never a model assertion or a persisted verdict.

    It proves only a clean, nonempty committed artifact, NOT functional completion.
    Path samples are independently bounded so wide diffs fit the terminal protocol.
    """
    base, commit = record.get('base'), record.get('commit')
    evidence = {'schema_version': 1, 'source': 'runtime_git', 'workspace_id': record['id'],
                'base_commit': base, 'artifact_commit': commit, 'net_diff_verified': False,
                'base_is_ancestor': False, 'changed_file_count': 0, 'changed_files': [],
                'changed_files_truncated': False, 'clean_worktree': False, 'head_matches_commit': False}
    if not valid_commit(root, base) or not valid_commit(root, commit):
        return evidence
    try:
        evidence['base_is_ancestor'] = git(root, 'merge-base', base, commit) == base
        names = git(root, 'diff', '--no-ext-diff', '--no-textconv', '--no-renames',
                    '--name-only', '-z', base, commit, '--', '.', ':(exclude).agent', strip=False)
        files = [name for name in names.split('\0') if name]
        evidence['net_diff_verified'] = True
        evidence['changed_file_count'] = len(files)
        budget = 0
        for name in files[:20]:
            size = escaped(name)
            if budget + size > 1200:
                break
            evidence['changed_files'].append(name)
            budget += size
        evidence['changed_files_truncated'] = len(evidence['changed_files']) < len(files)
        if workspace_tree_exists(root, record):
            tree = tree_path(root, record)
            evidence['clean_worktree'] = clean(tree)
            evidence['head_matches_commit'] = git(tree, 'rev-parse', 'HEAD') == commit
    except ValueError:
        evidence['net_diff_verified'] = False
    return evidence


def implementation_blocker(evidence):
    if not evidence['net_diff_verified'] or not evidence['base_is_ancestor']:
        return 'IMPLEMENTATION_EVIDENCE_INVALID'
    if evidence['changed_file_count'] == 0:
        return 'NO_IMPLEMENTATION_CHANGES'
    if not evidence['clean_worktree'] or not evidence['head_matches_commit']:
        return 'IMPLEMENTATION_EVIDENCE_INVALID'
    return None


def merge_blockers(root, record):
    """Complete, read-only merge preconditions; never touch uncommitted work."""
    tree = tree_path(root, record)
    exists = workspace_tree_exists(root, record)
    blockers = []
    if record['state'] != 'ready':
        blockers.append('WORKSPACE_NOT_READY')
    elif not record.get('reviewed'):
        blockers.append('REVIEW_REQUIRED')
    if record['state'] not in ('merged', 'discarded') and not exists:
        blockers.append('WORKSPACE_TREE_MISSING' if not tree.exists() else 'WORKSPACE_TREE_INVALID')
    base_valid = valid_commit(root, record.get('base'))
    if record['state'] not in ('merged', 'discarded') and not base_valid:
        blockers.append('WORKSPACE_BASE_INVALID')
    # Editing/failed worktrees need not have been sealed. A ready/reviewing record must.
    commit_required = record['state'] in ('ready', 'reviewing')
    commit_valid = valid_commit(root, record.get('commit'))
    if commit_required and not commit_valid:
        blockers.append('WORKSPACE_COMMIT_INVALID')
    if (exists and not clean(tree)) or not clean(root):
        blockers.append('UNCOMMITTED_CHANGES')
    if record['state'] not in ('merged', 'discarded') and base_valid and git(root, 'rev-parse', 'HEAD') != record['base']:
        blockers.append('PROJECT_MOVED_REVIEW_AGAIN')
    if exists and commit_valid and git(tree, 'rev-parse', 'HEAD') != record['commit']:
        blockers.append('WORKSPACE_CHANGED')
    if commit_required and exists and base_valid and commit_valid:
        artifact_blocker = implementation_blocker(implementation_evidence(root, record))
        if artifact_blocker and artifact_blocker not in blockers:
            blockers.append(artifact_blocker)
    return blockers


def allowed_actions(record, blockers):
    """Task-local recovery choices; merge is offered only when all checks pass."""
    state = record['state']
    if state not in ('ready', 'failed', 'merged', 'discarded'):
        return ['inspect']
    if state in ('merged', 'discarded'):
        return ['inspect']
    actions = ['inspect']
    if not blockers:
        actions.append('merge')
    actions.append('discard')
    return actions


def next_step(record, blockers):
    """One accurate recovery sentence; never promises an action that would be refused."""
    state = record['state']
    if state == 'editing':
        return '实现子任务仍在编辑：只能 inspect 读取；等子任务结束后再由主代理决定。'
    if state == 'reviewing':
        return 'review 子任务正在该工作区上运行：只能 inspect 读取；等它结束后再决定。'
    if state == 'failed':
        return '工作树改动已保留，且不是 review-ready：先 inspect 查看 diff 和 merge_blocked_by，' \
               '确认不需要后 discard，或作为新任务重新委派。'
    if state == 'merged':
        return '已合并：工作树已删除，记录只读。'
    if state == 'discarded':
        return '工作树已丢弃：记录只读。'
    if 'REVIEW_REQUIRED' in blockers:
        return '尚未 review：需要 review 子任务在该 workspace_id 上完成审查后才能 merge。'
    if blockers:
        return '暂时不能 merge（' + '、'.join(blockers) + '）：先按阻塞原因处理，再重新 inspect。'
    return '已通过 review：确认主项目干净且未移动后可 ff-only merge；merge 会删除该工作树。'


def recovery(root, record):
    """Read-only main-agent view: what is preserved, what blocks merge, what is still legal."""
    tree = tree_path(root, record)
    exists = workspace_tree_exists(root, record)
    head = git(tree, 'rev-parse', 'HEAD') if exists else None
    blockers = merge_blockers(root, record)
    return {
        'state': record['state'],
        'reviewed': bool(record.get('reviewed')),
        'tree_exists': exists,
        'uncommitted_changes': bool(exists and not clean(tree)),
        'project_uncommitted_changes': not clean(root),
        'committed_head': head,
        'recorded_commit': record.get('commit'),
        'head_matches_commit': bool(head is not None and record.get('commit') and head == record['commit']),
        'merge_ready': not blockers,
        'merge_blocked_by': blockers,
        'allowed_actions': allowed_actions(record, blockers),
        'next_step': next_step(record, blockers),
    }


def summary(root, record):
    out = dict(record)
    tree = tree_path(root, record)
    out['path'] = str(tree)
    if workspace_tree_exists(root, record) and valid_commit(root, record.get('base')):
        diff = git(tree, 'diff', '--no-ext-diff', '--no-textconv', '--stat', record['base'])
        out['diff_stat'] = diff[:2000]
    out.update(recovery(root, record))
    out['artifact_evidence'] = implementation_evidence(root, record)
    out['acceptance_verified'] = False  # Git cannot verify the user's business goal.
    out['delivery_state'] = ('artifact_ready_pending_review' if record['state'] == 'ready' and
                             implementation_blocker(out['artifact_evidence']) is None else
                             'no_changes' if record.get('delivery_failure_code') == 'NO_IMPLEMENTATION_CHANGES' else
                             'unverified')
    return out


def page(text, args, key):
    offset, limit = int(args.get('offset', 0)), int(args.get('limit', 4000))
    require(offset >= 0 and 1 <= limit <= 4000, 'INVALID_PAGE')
    end = min(len(text), offset + limit)
    if end > offset and escaped(text[offset:end]) > OUTPUT_BUDGET:
        # Control characters and U+2028 escape to six units; shrink until the page fits.
        low, high = offset + 1, end
        while low < high:
            middle = (low + high + 1) // 2
            if escaped(text[offset:middle]) <= OUTPUT_BUDGET:
                low = middle
            else:
                high = middle - 1
        end = low
    return {key: text[offset:end], 'next_offset': end if end < len(text) else None, 'total_chars': len(text)}


def response_line(result):
    """One JSON line the bridge can return intact; never a truncated, unparsable object."""
    line = json.dumps({'ok': True, **result}, ensure_ascii=False)
    if units(line) > STDOUT_LIMIT:
        line = json.dumps({'ok': False, 'code': 'WORKSPACE_OUTPUT_TOO_LARGE',
                           'hint': 'request a smaller page or line_count'})
    return line


def execute(args):
    root = project_path(args['project'])
    # Cross-process lock serializes file writes, sealing, review, integration and cleanup.
    import fcntl
    lock = internal(root, '.agent/workspace.lock')
    lock.parent.mkdir(parents=True, exist_ok=True)
    with open(lock, 'a') as handle:
        fcntl.flock(handle, fcntl.LOCK_EX)
        return locked(root, args)


def locked(root, args):
    action = args['action']
    if action == 'prepare':
        require(clean(root), 'PROJECT_HAS_UNCOMMITTED_CHANGES')
        require(shutil.disk_usage(root).free >= 512 * 1024 * 1024, 'WORKSPACE_DISK_SPACE_LOW')
        tasks = internal(root, '.agent/worktrees')
        tasks.mkdir(parents=True, exist_ok=True)
        task = uuid.uuid4().hex
        base = git(root, 'rev-parse', 'HEAD')
        record = {'id': task, 'base': base, 'state': 'editing', 'reviewed': False, 'lease_until': time.time() + 420}
        tree = tree_path(root, record)
        git(root, 'worktree', 'add', '--detach', str(tree), base)
        save(root, record)
        return summary(root, record)
    if action == 'list':
        if 'workspace_ids' in args:
            offset, limit = args.get('offset', 0), args.get('limit', 50)
            require(type(offset) is int and type(limit) is int and 0 <= offset <= 4096 and 1 <= limit <= 50,
                    'INVALID_PAGE')
            records, unavailable = list_authorized(root, args['workspace_ids'])
            end = offset + limit
            return {
                'workspaces': records[offset:end],
                'total_count': len(records),
                'truncated': end < len(records),
                'next_offset': end if end < max(len(records), len(unavailable)) else None,
                'unavailable_workspace_ids': unavailable[offset:end],
                'unavailable_count': len(unavailable),
            }
        folder = internal(root, '.agent/results')
        return {'workspaces': [json.loads(p.read_text()) for p in sorted(folder.glob('*.json'))[:50]
                               if re.fullmatch(r'[a-f0-9]{32}\.json', p.name) and not p.is_symlink()]}
    record = load(root, args['workspace_id'])
    tree = tree_path(root, record)
    if action == 'inspect':
        if record['state'] in ('editing', 'reviewing') and time.time() > record.get('lease_until', 0):
            record['state'] = 'failed' if record['state'] == 'editing' else 'ready'
            record['reviewed'] = False
            save(root, record)
        return summary(root, record)
    if action in ('read', 'list_files', 'write', 'delete', 'diff', 'search', 'replace'):
        require(record['state'] in ('editing', 'reviewing', 'ready', 'failed'), 'WORKSPACE_NOT_AVAILABLE')
        if action == 'diff':
            text = git(tree, 'diff', '--no-ext-diff', '--no-textconv', record['base'])
            return page(text, args, 'diff')
        if action == 'list_files':
            return page(git(tree, 'ls-files', '--cached', '--others', '--exclude-standard'), args, 'files')
        if action == 'search':
            return search(tree, args)
        p = safe_file(tree, args['path'])
        if action == 'read':
            text = file_text(p)
            if 'start_line' in args or 'line_count' in args:
                return read_lines(text, args)
            out = page(text, args, 'content')
            out['total_lines'] = len(lines_of(text))
            return out
        require(record['state'] == 'editing' and time.time() <= record.get('lease_until', 0), 'WORKSPACE_FROZEN')
        if action == 'replace':
            patch = len(str(args.get('new_text', '')).encode()) or 1
            written = record.get('written_bytes', 0) + patch
            require(written <= 16 * 1024 * 1024, 'WORKSPACE_WRITE_BUDGET')
            result = replace(p, args)
            record['written_bytes'] = written
            save(root, record)
            return result
        if action == 'write':
            text = args['content']
            if p.is_file() and p.stat().st_size > LIMIT:
                raise Refused('USE_REPLACE_FOR_LARGE_FILE', size_bytes=p.stat().st_size, max_bytes=LIMIT,
                              hint='whole-file write would overwrite a large file; use replace')
            if not isinstance(text, str) or len(text.encode()) > LIMIT:
                raise Refused('FILE_TOO_LARGE', max_bytes=LIMIT,
                              hint='write is for new or small files; create a skeleton then use replace')
            written = record.get('written_bytes', 0) + len(text.encode())
            require(written <= 16 * 1024 * 1024, 'WORKSPACE_WRITE_BUDGET')
            record['written_bytes'] = written
            save(root, record)
            p.parent.mkdir(parents=True, exist_ok=True)
            # O_NOFOLLOW plus symlink/component checks; no child has an arbitrary shell.
            fd = os.open(p, os.O_WRONLY | os.O_CREAT | os.O_TRUNC | _O_NOFOLLOW, 0o644)
            with os.fdopen(fd, 'w', encoding='utf-8') as out:
                out.write(text)
        else:
            require(p.is_file(), 'FILE_NOT_FOUND')
            p.unlink()
        return {'changed': args['path']}
    if action == 'renew':
        require(record['state'] in ('editing', 'reviewing'), 'WORKSPACE_LEASE_LOST')
        record['lease_until'] = time.time() + 420
        save(root, record)
        return {'ok': True, 'id': record['id']}
    if action == 'seal':
        require(record['state'] == 'editing' and time.time() <= record.get('lease_until', 0), 'WORKSPACE_NOT_EDITING')
        require(workspace_tree_exists(root, record), 'WORKSPACE_TREE_INVALID')
        require(valid_commit(root, record.get('base')), 'WORKSPACE_BASE_INVALID')
        git(tree, 'add', '-A', '--', '.', ':(exclude).agent')
        if git(tree, 'diff', '--cached', '--name-only', '--', '.', ':(exclude).agent'):
            git(tree, '-c', 'user.name=Eta Agent', '-c', 'user.email=agent@localhost', 'commit', '-m', 'Agent implementation')
        record.update(commit=git(tree, 'rev-parse', 'HEAD'), reviewed=False)
        evidence = implementation_evidence(root, record)
        blocker = implementation_blocker(evidence)
        if blocker:
            record.update(state='failed', delivery_failure_code=blocker)
            save(root, record)
            raise Refused(blocker, artifact_evidence=evidence, delivery_state='no_changes' if
                          blocker == 'NO_IMPLEMENTATION_CHANGES' else 'unverified',
                          acceptance_verified=False, next_step='inspect retained worktree; no automatic retry')
        record.update(state='ready')
        record.pop('delivery_failure_code', None)
        save(root, record)
    elif action == 'fail':
        if record['state'] in ('editing', 'ready'):
            record['state'] = 'failed'
            save(root, record)
    elif action == 'begin_review':
        require(record['state'] == 'ready' and clean(tree), 'WORKSPACE_NOT_READY')
        require(git(tree, 'rev-parse', 'HEAD') == record['commit'], 'WORKSPACE_CHANGED')
        blocker = implementation_blocker(implementation_evidence(root, record))
        if blocker:
            raise Refused(blocker)
        record.update(state='reviewing', reviewed=False, lease_until=time.time() + 420)
        save(root, record)
    elif action == 'end_review':
        # Cancellation may arrive just after review marked the workspace ready.
        require(record['state'] in ('reviewing', 'ready'), 'WORKSPACE_NOT_REVIEWING')
        record.update(state='ready', reviewed=False)
        save(root, record)
    elif action == 'review':
        require(record['state'] == 'reviewing' and clean(tree), 'WORKSPACE_NOT_READY')
        require(git(tree, 'rev-parse', 'HEAD') == record['commit'], 'WORKSPACE_CHANGED')
        record.update(state='ready', reviewed=True)
        save(root, record)
    elif action == 'merge':
        view = recovery(root, record)
        blockers = view['merge_blocked_by']
        if blockers:
            # Name the exact state/clean/head/commit reason; uncommitted work is never touched here.
            raise Refused(blockers[0], **{key: view[key] for key in (
                'state', 'reviewed', 'tree_exists', 'uncommitted_changes', 'project_uncommitted_changes', 'committed_head',
                'recorded_commit', 'head_matches_commit', 'merge_blocked_by', 'allowed_actions', 'next_step')})
        git(root, '-c', 'merge.autostash=false', 'merge', '--ff-only', record['commit'])
        record['state'] = 'merged'
        save(root, record)
        git(root, 'worktree', 'remove', str(tree))
    elif action == 'discard':
        require(record['state'] in ('ready', 'failed', 'merged', 'discarded'), 'WORKSPACE_IN_USE')
        if tree.exists():
            git(root, 'worktree', 'remove', '--force', str(tree))
        record['state'] = 'discarded'
        save(root, record)
    else:
        raise ValueError('UNKNOWN_WORKSPACE_ACTION')
    return summary(root, record)


if __name__ == '__main__':
    try:
        print(response_line(execute(json.loads(sys.argv[1]))))
    except Exception as error:
        code = str(error) if isinstance(error, ValueError) and re.fullmatch(r'[A-Z_0-9]+', str(error)) else 'WORKSPACE_OPERATION_FAILED'
        details = error.details if isinstance(error, Refused) else {}
        print(json.dumps({'ok': False, 'code': code, **details}, ensure_ascii=False))
