"""Arborist tests against a local Paper 26.3 server, with real vanilla trees.

Each pair test grows tree A and tree B apart (/place feature), copies both next to each other so their crowns touch
(B is copied over A, like a tree generated later), then the bot fells A by breaking its lowest log while sneaking with
an axe. After the crown has had time to fall it checks:

- every log of A is gone and dropped, and the axe took one damage per log
- how many leaves only A grew stayed up, and how many leaves only B grew fell
- for comparison, how many of A's leaves vanilla decay (and FastLeafDecay) would have left floating forever

Where the crowns overlap nobody can tell which tree grew a leaf; Arborist gives it to the nearest trunk, so a few
leaves A grew on B's side stay with B, and a few B grew right next to A's trunk go with A. Across the pairs at most
5% of A's own leaves may stay up and at most 4% of B's may fall (one random pair per species, so these vary from run
to run; measured offline on 3931 pairs of real trees: about 2% and 1.3%, while the old rule leaves 19% up).

    python tests.py            run everything (starts and stops the server itself)
    python tests.py pair       run the tests whose names start with these
    AR_ATTACH=1 python tests.py ...   use a server that is already running, with the bot already connected
"""
import json
import os
import re
import sys
import time
import traceback
from collections import deque

from mcbot import Bot
from server import Server, DIR

NAME = 'Tester'
GROUND = -61
Y0 = GROUND + 1
results = []
server = None
bot = None


def log(*args):
    print(*args, flush=True)


def check(cond, what):
    if not cond:
        raise AssertionError(what)


def arb(text):
    return server.arb(text)


def status():
    return server.cmd('arborist status')


def status_number(pattern):
    m = re.search(pattern, status())
    return int(m.group(1)) if m else None


def snapshot(name, box):
    arb('snapshot %s %d %d %d %d %d %d' % ((name,) + tuple(box)))
    data = server.json(name)
    leaves = {tuple(l[:3]): l for l in data['leaves']}
    logs = {tuple(l[:3]): l[3] for l in data['logs']}
    return leaves, logs, data['items']


def place(feature, x, z):
    reply = server.cmd('place feature minecraft:%s %d %d %d' % (feature, x, Y0, z))
    return reply.startswith('Placed')


def clear(box):
    x1, y1, z1, x2, y2, z2 = box
    arb('clear %d %d %d %d %d %d %d' % (x1, GROUND - 3, z1, x2, y2, z2, GROUND))


def vanilla_left(leaves, logs, removed_logs):
    """Leaves vanilla would keep (distance <= 6 through any leaves from any remaining log)."""
    remaining = set(logs) - set(removed_logs)
    dist = {}
    queue = deque()
    for (x, y, z) in remaining:
        for dx, dy, dz in ((1, 0, 0), (-1, 0, 0), (0, 1, 0), (0, -1, 0), (0, 0, 1), (0, 0, -1)):
            p = (x + dx, y + dy, z + dz)
            if p in leaves and p not in dist:
                dist[p] = 1
                queue.append(p)
    while queue:
        p = queue.popleft()
        if dist[p] >= 6:
            continue
        x, y, z = p
        for dx, dy, dz in ((1, 0, 0), (-1, 0, 0), (0, 1, 0), (0, -1, 0), (0, 0, 1), (0, 0, -1)):
            q = (x + dx, y + dy, z + dz)
            if q in leaves and q not in dist:
                dist[q] = dist[p] + 1
                queue.append(q)
    return {p for p in leaves if p in dist}


def touching(a, b):
    return any((x + dx, y + dy, z + dz) in b for (x, y, z) in a
               for dx in (-1, 0, 1) for dy in (-1, 0, 1) for dz in (-1, 0, 1))


def nearest(p, logs):
    return min((p[0] - q[0]) ** 2 + (p[1] - q[1]) ** 2 + (p[2] - q[2]) ** 2 for q in logs)


def wait_until_quiet(timeout=15):
    """Waits until no leaves are about to fall any more."""
    deadline = time.time() + timeout
    time.sleep(1.0)
    while time.time() < deadline:
        m = re.search(r'leaves about to fall: (\d+)', status())
        if m and m.group(1) == '0':
            time.sleep(0.6)  # the next batch of orphan checks
            m = re.search(r'leaves about to fall: (\d+)', status())
            if m and m.group(1) == '0':
                return
        time.sleep(0.3)
    raise AssertionError('leaves still falling after %ds' % timeout)


def grow(feature, x, z, box):
    for attempt in range(4):
        clear(box)
        if place(feature, x, z):
            return
    raise AssertionError('could not place ' + feature)


# ---------------------------------------------------------------- pair tests

PAIRS = [
    # name, feature A, feature B, x distance between the two trunks
    ('oak', 'oak', 'oak', 4),
    ('oak_close', 'oak', 'oak', 3),
    ('fancy_oak', 'fancy_oak', 'fancy_oak', 7),
    ('birch', 'birch', 'birch', 3),
    ('spruce', 'spruce', 'spruce', 4),
    ('pine', 'pine', 'spruce', 3),
    ('mega_spruce', 'mega_spruce', 'mega_spruce', 7),
    ('jungle', 'jungle_tree', 'jungle_tree', 4),
    ('mega_jungle', 'mega_jungle_tree', 'mega_jungle_tree', 9),
    ('jungle_bush', 'jungle_bush', 'oak', 3),
    ('dark_oak', 'dark_oak', 'dark_oak', 5),
    ('acacia', 'acacia', 'acacia', 5),
    ('cherry', 'cherry', 'cherry', 7),
    ('mangrove', 'mangrove', 'mangrove', 6),
    ('pale_oak', 'pale_oak', 'pale_oak', 6),
    ('azalea', 'azalea_tree', 'azalea_tree', 5),
    ('poplar', 'red_poplar', 'yellow_poplar', 6),
    ('orange_poplar', 'orange_poplar', 'orange_poplar', 6),
    ('oak_birch', 'oak', 'birch', 3),
]
R = 16  # half width of a tree's box
TOP = Y0 + 50


def pair_test(index, name, feature_a, feature_b, gap):
    x = 2000 + index * 80
    za, zb, zp = 200, 300, 0
    arb('load %d %d %d %d' % (x - R - 4, -R - 4, x + gap + R + 4, zb + R + 4))
    box_a = (x - R, GROUND, za - R, x + R, TOP, za + R)
    box_b = (x - R, GROUND, zb - R, x + R, TOP, zb + R)
    box_p = (x - R, GROUND, zp - R, x + gap + R, TOP, zp + R)
    grow(feature_a, x, za, box_a)
    leaves_a, logs_a, _ = snapshot('a', box_a)
    for attempt in range(10):
        # Trunks that touch are one tree (felled and owned together), which is not what this test is about.
        grow(feature_b, x, zb, box_b)
        leaves_b, logs_b, _ = snapshot('b', box_b)
        a_at = {(p[0], p[1], p[2] - za) for p in logs_a}
        b_at = {(p[0] + gap, p[1], p[2] - zb) for p in logs_b}
        if not touching(a_at, b_at):
            break
    else:
        raise AssertionError('B kept growing into A')
    clear(box_p)
    arb('clone %d %d %d %d %d %d %d %d %d' % (box_a + (x - R, GROUND, zp - R)))
    arb('clone %d %d %d %d %d %d %d %d %d' % (box_b + (x - R + gap, GROUND, zp - R)))
    shift_a = lambda p: (p[0], p[1], p[2] - za + zp)
    shift_b = lambda p: (p[0] + gap, p[1], p[2] - zb + zp)
    sa = {shift_a(p) for p in leaves_a}
    sb = {shift_b(p) for p in leaves_b}
    a_logs = {shift_a(p) for p in logs_a}
    before, logs_before, _ = snapshot('before', box_p)
    check(a_logs <= set(logs_before), 'A logs were not copied')
    a_only = (sa - sb) & set(before)
    b_only = (sb - sa) & set(before)
    shared = sa & sb
    old_left = len(a_only & vanilla_left(before, logs_before, a_logs))

    cut = min(a_logs, key=lambda p: (p[1], abs(p[0] - x) + abs(p[2] - zp)))
    arb('tp %s %d %d %d' % (NAME, x, Y0, zp - R - 2))
    arb('killitems %d %d %d %d %d %d' % box_p)
    arb('gear %s diamond_axe 0' % NAME)
    reply = arb('break %s %d %d %d 1' % ((NAME,) + cut))
    check('broken=true' in reply, 'cut failed: ' + reply)
    wait_until_quiet()
    after, logs_after, items = snapshot('after', box_p)
    damage = int(re.search(r'damage=(-?\d+)', arb('hand ' + NAME)).group(1))

    a_logs_left = a_logs & set(logs_after)
    a_left = a_only & set(after)
    b_lost = b_only - set(after)
    shared_left = shared & set(after)
    b_logs = {shift_b(p) for p in logs_b}
    a_side = lambda p: nearest(p, a_logs) < nearest(p, b_logs)
    b_side = lambda p: nearest(p, b_logs) < nearest(p, a_logs)
    a_left_own_side = {p for p in a_left if a_side(p)}
    b_lost_own_side = {p for p in b_lost if b_side(p)}
    log_items = sum(n for k, n in items.items() if k.endswith('_log'))
    line = ('%-14s A %3d logs %4d leaves | B %4d leaves | shared %3d | after: A leaves left %3d (%d on A side), '
            'B leaves lost %d (%d on B side), shared left %3d | old rule would leave %3d | axe %d, log items %d') % (
        name, len(a_logs), len(sa), len(sb), len(shared), len(a_left), len(a_left_own_side), len(b_lost),
        len(b_lost_own_side), len(shared_left), old_left, damage, log_items)
    log('  ' + line)
    check(not a_logs_left, 'A logs left: %s' % sorted(a_logs_left)[:5])
    check(damage == len(a_logs), 'axe damage %d for %d logs' % (damage, len(a_logs)))
    check(log_items == len(a_logs), 'log drops %d for %d logs' % (log_items, len(a_logs)))
    return {'name': name, 'a_left': len(a_left), 'a_only': len(a_only), 'old_left': old_left, 'b_lost': len(b_lost),
            'b_only': len(b_only)}


def t01_pairs():
    worst = []
    for i, (name, a, b, gap) in enumerate(PAIRS):
        r = pair_test(i, name, a, b, gap)
        worst.append(r)
    total_left = sum(r['a_left'] for r in worst)
    total_only = sum(r['a_only'] for r in worst)
    total_old = sum(r['old_left'] for r in worst)
    total_lost = sum(r['b_lost'] for r in worst)
    total_b = sum(r['b_only'] for r in worst)
    log('  total: %d of %d leaves only A grew stayed up (old rule: %d); B lost %d of %d'
        % (total_left, total_only, total_old, total_lost, total_b))
    check(total_left <= 0.05 * total_only, 'too many leaves of the cut trees stayed up')
    check(total_left * 4 <= total_old, 'not much better than the old rule')
    check(total_lost <= 0.04 * total_b, 'the standing trees lost too many leaves')


# ---------------------------------------------------------------- behaviour tests

def single_tree(x, z, feature='oak'):
    box = (x - R, GROUND, z - R, x + R, TOP, z + R)
    arb('load %d %d %d %d' % (x - R - 4, z - R - 4, x + R + 4, z + R + 4))
    grow(feature, x, z, box)
    leaves, logs, _ = snapshot('single', box)
    arb('tp %s %d %d %d' % (NAME, x, Y0, z - R - 2))
    arb('killitems %d %d %d %d %d %d' % box)
    return box, leaves, logs


def column(logs, x, z):
    return sorted(p for p in logs if p[0] == x and p[2] == z)


def t02_one_log_at_a_time():
    """Without sneaking every break takes one log; the crown stays until the last one, then falls."""
    box, leaves, logs = single_tree(4000, 0)
    trunk = sorted(logs, key=lambda p: p[1])
    arb('gear %s iron_axe 0' % NAME)
    for i, p in enumerate(trunk):
        check('broken=true' in arb('break %s %d %d %d 0' % ((NAME,) + p)), 'break failed')
        time.sleep(1.5 if i < len(trunk) - 1 else 0)
        now, now_logs, _ = snapshot('step', box)
        if i < len(trunk) - 1:
            check(len(now_logs) == len(trunk) - i - 1, 'more than one log went: %d left' % len(now_logs))
            check(len(set(leaves) & set(now)) >= len(leaves) - 3, 'leaves fell while logs were left')
    wait_until_quiet()
    now, _, _ = snapshot('step', box)
    check(not (set(leaves) & set(now)), '%d leaves left after the last log' % len(set(leaves) & set(now)))
    log('  %d logs one by one, crown of %d leaves fell after the last' % (len(trunk), len(leaves)))


def t03_build_next_to_tree():
    """Planks touching an upper log: sneak-felling only breaks the cut log."""
    box, leaves, logs = single_tree(4100, 0)
    trunk = sorted(logs, key=lambda p: p[1])
    top = trunk[-2]
    arb('setblock %d %d %d oak_planks' % (top[0], top[1], top[2] - 1))
    refused = status_number(r'(\d+) fellings refused')
    arb('gear %s iron_axe 0' % NAME)
    arb('break %s %d %d %d 1' % ((NAME,) + trunk[0]))
    time.sleep(1.5)
    _, now_logs, _ = snapshot('step', box)
    check(len(now_logs) == len(trunk) - 1, 'felled next to a build: %d logs left' % len(now_logs))
    check(status_number(r'(\d+) fellings refused') == refused + 1, 'refusal not counted')
    log('  felling refused, %d of %d logs still standing' % (len(now_logs), len(trunk)))


def t04_placed_log():
    """A log placed by a player is never part of a tree: felling refuses, and the crown still falls without it."""
    box, leaves, logs = single_tree(4200, 0)
    trunk = sorted(logs, key=lambda p: p[1])
    side = (trunk[1][0] + 1, trunk[1][1], trunk[1][2])
    check('placed' in arb('placelog %s %d %d %d oak_log' % ((NAME,) + side)), 'place failed')
    arb('gear %s iron_axe 0' % NAME)
    arb('break %s %d %d %d 1' % ((NAME,) + trunk[0]))
    time.sleep(1.5)
    _, now_logs, _ = snapshot('step', box)
    check(len(now_logs) == len(trunk), 'felled next to a placed log: %d logs left' % len(now_logs))
    for p in trunk[1:]:
        arb('break %s %d %d %d 0' % ((NAME,) + p))
    wait_until_quiet()
    now, now_logs, _ = snapshot('step', box)
    check(set(now_logs) == {side}, 'placed log should stay alone: %s' % now_logs)
    near = [p for p in now if p in leaves]
    check(not near, '%d leaves kept by the placed log' % len(near))
    log('  placed log refused the felling and kept no leaves (vanilla would keep them)')


def t05_axe_protection():
    """With 3 durability left the felling stops before the axe breaks; a stump is left."""
    box, leaves, logs = single_tree(4300, 0, 'fancy_oak')
    trunk = sorted(logs, key=lambda p: p[1])
    arb('gear %s diamond_axe 1558' % NAME)
    arb('break %s %d %d %d 1' % ((NAME,) + trunk[0]))
    time.sleep(1.0)
    hand = arb('hand ' + NAME)
    _, now_logs, _ = snapshot('step', box)
    check('type=diamond_axe' in hand and 'damage=1560' in hand, 'axe: ' + hand)
    check(len(now_logs) == len(logs) - 2, '%d of %d logs left' % (len(now_logs), len(logs)))
    log('  axe kept at 1 durability, 2 of %d logs cut, the rest stays' % len(logs))


def t06_explosion():
    box, leaves, logs = single_tree(4400, 0)
    trunk = sorted(logs, key=lambda p: p[1])
    mid = trunk[len(trunk) // 2]
    arb('explode %d %d %d 7' % mid)
    wait_until_quiet()
    now, now_logs, _ = snapshot('step', box)
    left = set(leaves) & set(now)
    check(not now_logs, 'explosion left logs (test setup): %s' % now_logs)
    check(not left, '%d leaves left after the explosion' % len(left))
    log('  explosion took the trunk, the rest of the crown fell')


def t07_restart_keeps_partly_cut_tree():
    if os.environ.get('AR_ATTACH'):
        log('  skipped (attached to a running server)')
        return
    global bot
    # Two touching oaks; cut A's lowest log, restart, then cut the rest of A one by one.
    x, z, gap = 5000 + (int(time.time()) % 50) * 80, 0, 4  # a fresh spot: no record left by an earlier run
    arb('load %d %d %d %d' % (x - R - 4, z - R - 4, x + gap + R + 4, 300 + R + 4))
    box_a = (x - R, GROUND, 200 - R, x + R, TOP, 200 + R)
    box_b = (x - R, GROUND, 300 - R, x + R, TOP, 300 + R)
    box_p = (x - R, GROUND, z - R, x + gap + R, TOP, z + R)
    grow('oak', x, 200, box_a)
    grow('oak', x, 300, box_b)
    clear(box_p)
    arb('clone %d %d %d %d %d %d %d %d %d' % (box_a + (x - R, GROUND, z - R)))
    arb('clone %d %d %d %d %d %d %d %d %d' % (box_b + (x - R + gap, GROUND, z - R)))
    before, logs, _ = snapshot('before', box_p)
    a_logs = sorted((p for p in logs if p[0] == x), key=lambda p: p[1])
    arb('tp %s %d %d %d' % (NAME, x, Y0, z - R - 2))
    arb('gear %s iron_axe 0' % NAME)
    tracked = status_number(r'Partly cut trees: (\d+)')
    arb('break %s %d %d %d 0' % ((NAME,) + a_logs[0]))
    time.sleep(1)
    check(status_number(r'Partly cut trees: (\d+)') == tracked + 1, 'tree not recorded')
    bot.close_socket()
    server.stop()
    check(os.path.isfile(os.path.join(DIR, 'plugins', 'Arborist', 'trees.dat')), 'trees.dat missing')
    server.start()
    bot = Bot(NAME)
    bot.connect()
    arb('load %d %d %d %d' % (x - R - 4, z - R - 4, x + gap + R + 4, z + R + 4))
    check(status_number(r'Partly cut trees: (\d+)') == tracked + 1, 'tree forgotten after restart')
    arb('tp %s %d %d %d' % (NAME, x, Y0, z - R - 2))
    arb('gear %s iron_axe 0' % NAME)
    for p in a_logs[1:]:
        arb('break %s %d %d %d 0' % ((NAME,) + p))
    wait_until_quiet()
    after, _, _ = snapshot('after', box_p)
    a_side = [p for p in after if p[0] < x + 2 and p in before]
    check(not a_side, '%d leaves on the cut side left after the restart' % len(a_side))
    log('  partly cut tree survived the restart; its crown fell after the last log')


def t08_dark_forest():
    """Fells trees in a real dark forest and measures the cost; no leaf may be left that nothing keeps alive."""
    world = 'arbforest%d' % (int(time.time()) % 1000000)  # a new forest every run
    arb('makeworld %s %d' % (world, int(time.time())))
    arb('world ' + world)
    loc = server.cmd('execute in minecraft:%s run locate biome minecraft:dark_forest' % world)
    m = re.search(r'\[(-?\d+), (?:~|-?\d+), (-?\d+)\]', loc)
    check(m, 'no dark forest: ' + loc)
    cx, cz = int(m.group(1)) + 40, int(m.group(2)) + 40
    arb('load %d %d %d %d' % (cx - 72, cz - 72, cx + 72, cz + 72))
    arb('tp %s %d surface %d %s' % (NAME, cx, cz, world))
    time.sleep(3)
    arb('trees trees %d %d %d %d' % (cx - 40, cz - 40, cx + 40, cz + 40))
    bases = server.json('trees')['trees']
    trees = []
    for t in bases:  # one base per tree: skip columns next to a base already taken
        if all(abs(t[0] - u[0]) > 1 or abs(t[2] - u[2]) > 1 for u in trees):
            trees.append(t)
    box = (cx - 60, -64, cz - 60, cx + 60, 200, cz + 60)
    before, logs_before, _ = snapshot('forest_before', box)
    arb('gear %s netherite_axe 0' % NAME)
    felled = 0
    for t in trees[:40]:
        reply = arb('break %s %d %d %d 1' % (NAME, t[0], t[1], t[2]))
        felled += 'broken=true' in reply
        time.sleep(0.1)
    wait_until_quiet(30)
    after, logs_after, _ = snapshot('forest_after', box)
    # Leaves the cutting left without support. Worldgen also leaves some leaves already decaying (vanilla removes
    # them slowly with random ticks); those were orphans before and are not Arborist's business.
    alive_before = vanilla_left(before, logs_before, [])
    alive = vanilla_left(after, logs_after, [])
    orphans = [p for p, l in after.items() if l[4] == 0 and p not in alive and p in alive_before
               and abs(p[0] - cx) < 48 and abs(p[2] - cz) < 48]
    st = status()
    log('  %d trunk bases, %d trees; felled %d (%d logs, %d -> %d leaves in the area); %d leaves left that nothing '
        'keeps alive' % (len(bases), len(trees), felled, len(logs_before) - len(logs_after), len(before), len(after),
                         len(orphans)))
    for line in st.splitlines():
        if 'analyses' in line or 'checks' in line:
            log('  ' + line.strip())
    arb('world world')
    check(felled >= 10, 'only %d trees felled' % felled)
    check(not orphans, 'orphan leaves left: %s' % orphans[:5])


TESTS = [t01_pairs, t02_one_log_at_a_time, t03_build_next_to_tree, t04_placed_log, t05_axe_protection,
         t06_explosion, t07_restart_keeps_partly_cut_tree, t08_dark_forest]


def main():
    global server, bot
    wanted = sys.argv[1:]
    tests = [t for t in TESTS if not wanted or any(t.__name__.startswith(w) or t.__name__[4:].startswith(w)
                                                    for w in wanted)]
    server = Server(log)
    attach = bool(os.environ.get('AR_ATTACH'))
    if attach:
        from server import Rcon
        server.rcon = Rcon(server.password)
    else:
        server.start()
        bot = Bot(NAME)
        bot.connect()
    mark = server.console_mark()
    try:
        for test in tests:
            log('%s' % test.__name__)
            start = time.time()
            try:
                test()
                results.append((test.__name__, 'PASS', ''))
                log('  PASS (%.0fs)' % (time.time() - start))
            except Exception as e:  # noqa
                results.append((test.__name__, 'FAIL', repr(e)))
                log('  FAIL: %r' % e)
                traceback.print_exc()
    finally:
        console = server.console_since(mark)
        problems = [l for l in console.splitlines() if 'Arborist' in l and ('WARN' in l or 'ERROR' in l)
                    or 'Exception' in l and 'oshi' not in l and 'Win32Exception' not in l]
        if not attach:
            server.stop()
    log('')
    for name, outcome, why in results:
        log('%-40s %s %s' % (name, outcome, why))
    log('console problems: %d' % len(problems))
    for line in problems[:20]:
        log('  ' + line)
    sys.exit(0 if all(r[1] == 'PASS' for r in results) and not problems else 1)


if __name__ == '__main__':
    main()
