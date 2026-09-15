"""주차별 비교 그래프 생성 (브리프 10장 산출물 4)

원본은 results/<주차>/raw/*.json 과 Prometheus 다. 이 스크립트는 그 둘에서
숫자를 뽑아 charts/data.json 에 캐시하고, 캐시만으로 PNG 를 다시 그린다.
Prometheus 보존 기간(30일)이 지나도 캐시가 있으면 그래프를 재생성할 수 있다.

사용법:
    python tools/make-charts.py                 # 캐시가 있으면 캐시로 그린다
    python tools/make-charts.py --refresh       # Prometheus 를 다시 읽는다 (스택이 떠 있어야 한다)

색은 dataviz 기준 팔레트의 categorical 슬롯 1~5 를 순서대로 쓴다 (W0~W4 고정).
라이트/다크 두 벌을 만들고, 문서에서 <picture> 로 테마에 맞춰 고른다.
캡션에 유니코드 마이너스(U+2212)를 쓰지 않는다 — Malgun Gothic 에 글리프가 없다.
"""

import argparse
import csv
import datetime
import json
import math
import os
import statistics as st
import urllib.parse
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PROM = 'http://localhost:9090'

# ramp.js 의 계단 정의와 같은 값을 본다
TARGETS = [200, 500, 1000, 2000]
RAMP_SEC, HOLD_SEC, START_SEC = 20, 120, 35

STRATEGIES = ['W0', 'W1', 'W2', 'W3', 'W4']
LABELS = {
    'W0': 'W0 로컬 락', 'W1': 'W1 비관적 락', 'W2': 'W2 낙관적 락',
    'W3': 'W3 분산 락', 'W4': 'W4 Redis 원자',
}

# dataviz 기준 팔레트 (validate_palette.js 로 라이트/다크 모두 통과 확인)
THEME = {
    'light': dict(surface='#fcfcfb', ink='#0b0b0b', ink2='#52514e', grid='#e3e2dd',
                  series=['#2a78d6', '#eb6834', '#1baf7a', '#eda100', '#e87ba4']),
    'dark': dict(surface='#1a1a19', ink='#ffffff', ink2='#c3c2b7', grid='#383835',
                 series=['#3987e5', '#d95926', '#199e70', '#c98500', '#d55181']),
}


# ── Prometheus ────────────────────────────────────────────────────────
def prom_range(expr, start, end, step='10s'):
    url = PROM + '/api/v1/query_range?' + urllib.parse.urlencode(
        {'query': expr, 'start': start, 'end': end, 'step': step})
    result = json.load(urllib.request.urlopen(url))['data']['result']
    return [(float(t), float(v)) for t, v in result[0]['values']] if result else []


def mean(values, default=0.0):
    return st.mean(values) if values else default


# ── 추출 ──────────────────────────────────────────────────────────────
def load_runs(week):
    with open(os.path.join(ROOT, 'results', week, 'runs.tsv'), encoding='utf-8-sig') as f:
        return list(csv.DictReader(f, delimiter='\t'))


def k6_metrics(week, strategy, scenario, run):
    path = os.path.join(ROOT, 'results', week, 'raw', f'{strategy}-{scenario}-run{run}.json')
    with open(path, encoding='utf-8') as f:
        return json.load(f)['metrics']


def extract(week):
    runs = load_runs(week)
    data = {'week': week, 'ramp': {}, 'soak': {}, 'chaos': {}}

    # ramp — 계단별 P99(k6) / 처리량·커넥션 대기(Prometheus), 3회 평균
    for s in STRATEGIES:
        rows = [r for r in runs if r['scenario'] == 'ramp' and r['strategy'] == s]
        p99, throughput, acquire = [], [], []
        for i, target in enumerate(TARGETS):
            run_p99, run_thr, run_acq = [], [], []
            for r in rows:
                m = k6_metrics(week, s, 'ramp', r['run'])
                key = f'http_req_duration{{stage:vu{target}}}'
                if key in m:
                    run_p99.append(m[key]['p(99)'])
                t0 = datetime.datetime.fromisoformat(r['timestamp']).timestamp()
                # 계단 유지 구간만 본다 (상승 20초와 그 직후 20초는 제외)
                a = t0 + START_SEC + i * (RAMP_SEC + HOLD_SEC) + RAMP_SEC + 20
                b = t0 + START_SEC + (i + 1) * (RAMP_SEC + HOLD_SEC) - 5
                run_thr.append(mean([v for _, v in prom_range(
                    f'sum(rate(coupon_issue_total{{strategy="{s}",result="ISSUED"}}[30s]))', a, b)]))
                run_acq.append(mean([v for _, v in prom_range(
                    f'rate(hikaricp_connections_acquire_seconds_sum{{strategy="{s}"}}[30s])'
                    f'/rate(hikaricp_connections_acquire_seconds_count{{strategy="{s}"}}[30s])*1000', a, b)]))
            p99.append(mean(run_p99))
            throughput.append(mean(run_thr))
            acquire.append(mean(run_acq))
        data['ramp'][s] = {'p99_ms': p99, 'throughput': throughput, 'acquire_ms': acquire}

    # soak — 분당 처리량 곡선 (실행별)
    for r in [x for x in runs if x['scenario'] == 'soak']:
        s = r['strategy']
        t0 = datetime.datetime.fromisoformat(r['timestamp']).timestamp()
        series = prom_range(
            f'sum(rate(coupon_issue_total{{strategy="{s}",result="ISSUED"}}[1m]))',
            t0 + START_SEC + 25, t0 + float(r['elapsed_s']) - 30, step='30s')
        base = series[0][0] if series else 0
        data['soak'].setdefault(s, {})[r['run']] = {
            'minutes': [round((t - base) / 60, 2) for t, _ in series],
            'rate': [v for _, v in series],
        }

    # chaos — "답한 횟수" 와 "기억하는 수" 의 차이
    for r in [x for x in runs if x['scenario'] == 'chaos']:
        data['chaos'].setdefault(r['strategy'], {})[r['run']] = \
            int(r['k6_issued']) - int(r['db_rows'])
    return data


def changepoint(rate, min_seg=10):
    """가장 큰 계단 하락 지점 — 앞뒤를 각각 5분(10샘플) 이상으로 나눴을 때
    (앞 평균 - 뒤 평균) / 앞 평균 이 최대가 되는 인덱스. 기준 구간을 고정하면
    하락이 늦게 온 실행을 놓친다."""
    best = None
    for i in range(min_seg, len(rate) - min_seg):
        before, after = st.mean(rate[:i]), st.mean(rate[i:])
        drop = (before - after) / before if before else 0
        if best is None or drop > best['drop']:
            best = {'drop': drop, 'index': i, 'before': before, 'after': after}
    return best


# ── 그리기 ────────────────────────────────────────────────────────────
def setup_matplotlib():
    import matplotlib
    matplotlib.use('Agg')
    from matplotlib import font_manager
    for path in ('C:/Windows/Fonts/malgun.ttf', '/usr/share/fonts/truetype/nanum/NanumGothic.ttf'):
        if os.path.exists(path):
            font_manager.fontManager.addfont(path)
            matplotlib.rcParams['font.family'] = font_manager.FontProperties(fname=path).get_name()
            break
    matplotlib.rcParams['axes.unicode_minus'] = False
    import matplotlib.pyplot as plt
    return plt


def style_axes(ax, theme):
    ax.set_facecolor(theme['surface'])
    ax.grid(True, color=theme['grid'], linewidth=0.8, alpha=0.9)
    ax.set_axisbelow(True)
    for side in ('top', 'right'):
        ax.spines[side].set_visible(False)
    for side in ('left', 'bottom'):
        ax.spines[side].set_color(theme['grid'])
    ax.tick_params(colors=theme['ink2'], labelsize=9)
    ax.xaxis.label.set_color(theme['ink2'])
    ax.yaxis.label.set_color(theme['ink2'])
    ax.title.set_color(theme['ink'])


def direct_labels(ax, entries, min_gap=0.05):
    """오른쪽 끝 직접 라벨. 겹치면 축 좌표 기준으로 벌린다.
    entries: [(y값, 글자, 색)] — 색만으로 계열을 구분하지 않기 위한 장치다.

    축 한계에서 비율을 직접 계산한다. transData 는 그리기 직전까지 확정되지
    않아, 이 시점에 읽으면 라벨이 엉뚱한 곳에 놓인다."""
    ax.autoscale_view()
    lo, hi = ax.get_ylim()
    ax.set_ylim(lo, hi)          # 이후 자동 스케일로 축이 바뀌지 않게 고정
    log = ax.get_yscale() == 'log'

    def fraction(y):
        if log:
            if y <= 0:
                return 0.0
            return (math.log10(y) - math.log10(lo)) / (math.log10(hi) - math.log10(lo))
        return (y - lo) / (hi - lo)

    placed = []
    for y, text, color in sorted(entries, key=lambda e: e[0]):
        fy = min(max(fraction(y), 0.0), 1.0)
        if placed and fy - placed[-1] < min_gap:
            fy = placed[-1] + min_gap
        placed.append(fy)
        ax.annotate(text, (1.0, fy), xycoords='axes fraction', xytext=(7, 0),
                    textcoords='offset points', color=color, fontsize=9,
                    va='center', fontweight='bold', annotation_clip=False)


def figure_legend(fig, theme, handles, labels, y=0.965):
    fig.legend(handles, labels, loc='upper left', bbox_to_anchor=(0.008, y),
               ncol=len(labels), frameon=False, fontsize=9.5,
               labelcolor=theme['ink2'], handlelength=1.6, columnspacing=1.8)


def caption(fig, theme, text, y=-0.02):
    fig.text(0.008, y, text, fontsize=9, color=theme['ink2'], ha='left', va='top')


def save(fig, path, theme):
    fig.savefig(path, dpi=160, facecolor=theme['surface'], bbox_inches='tight', pad_inches=0.4)
    print('  ', os.path.relpath(path, ROOT))


def chart_ramp(plt, data, theme, out):
    fig, axes = plt.subplots(1, 3, figsize=(14, 4.8))
    fig.patch.set_facecolor(theme['surface'])
    fig.subplots_adjust(top=0.78, wspace=0.34)
    x = list(range(len(TARGETS)))
    panels = [
        ('발급 처리량', 'throughput', '초당 발급 건수', True),
        ('응답 시간 P99', 'p99_ms', '밀리초', True),
        ('커넥션 획득 대기', 'acquire_ms', '밀리초', False),
    ]
    handles = None
    for ax, (title, key, ylabel, logy) in zip(axes, panels):
        style_axes(ax, theme)
        ax.set_title(title, fontsize=12, pad=10, loc='left')
        ax.set_xlabel('동시 사용자 수')
        ax.set_ylabel(ylabel)
        ax.set_xticks(x)
        ax.set_xticklabels([f'{t:,}' for t in TARGETS])
        if logy:
            ax.set_yscale('log')
        lines = []
        for i, s in enumerate(STRATEGIES):
            lines.append(ax.plot(x, data['ramp'][s][key], color=theme['series'][i],
                                 linewidth=2, marker='o', markersize=5,
                                 label=LABELS[s], zorder=3)[0])
        handles = handles or lines

        if key == 'p99_ms':
            ax.axhline(500, color=theme['ink2'], linewidth=1.2, linestyle=(0, (4, 3)), zorder=2)
            ax.annotate('NFR-03 판정선 500ms', (0.03, 540), xycoords=('axes fraction', 'data'),
                        color=theme['ink2'], fontsize=8.5, va='bottom')

        last = [(data['ramp'][s][key][-1], s, theme['series'][i]) for i, s in enumerate(STRATEGIES)]
        if key == 'acquire_ms':
            # 0 에 붙은 셋은 한 줄로 묶는다 — 라벨 세 개가 겹쳐 읽히지 않는다
            zero = [e for e in last if e[0] < 1]
            rest = [e for e in last if e[0] >= 1]
            direct_labels(ax, rest)
            if zero:
                ax.annotate(' · '.join(e[1] for e in zero) + ' = 0 에 가깝다', (1.0, 0.0),
                            xycoords='axes fraction', xytext=(7, 0), textcoords='offset points',
                            color=theme['ink2'], fontsize=9, va='center',
                            fontweight='bold', annotation_clip=False)
        else:
            direct_labels(ax, last)
        ax.set_xlim(-0.15, len(TARGETS) - 1 + 0.12)

    fig.suptitle('실험 A 1단계 — 동시성을 올릴 때 다섯 전략은 어떻게 갈리는가  (ramp, 3회 평균)',
                 fontsize=13.5, color=theme['ink'], x=0.008, ha='left', y=1.0)
    figure_legend(fig, theme, handles, [LABELS[s] for s in STRATEGIES], y=0.93)
    caption(fig, theme,
            '처리량은 동시성을 10배 올려도 늘지 않는다 - 다섯 전략 모두 직렬 처리이기 때문이다. '
            '늘어나는 것은 줄 서는 시간(P99)뿐이다.\n'
            '커넥션 대기는 락이 DB 안에 있는 W1·W2 에서만 발생하며, 톰캣 스레드 200개가 상한이라 '
            '200 VU 에서 이미 포화해 그 뒤로 평평하다.')
    save(fig, out, theme)
    plt.close(fig)


def chart_soak(plt, data, theme, out):
    fig, axes = plt.subplots(1, 2, figsize=(13.5, 4.8))
    fig.patch.set_facecolor(theme['surface'])
    fig.subplots_adjust(top=0.78, wspace=0.26)

    ax = axes[0]
    style_axes(ax, theme)
    ax.set_title('다섯 전략, 30분 (1회차)', fontsize=12, pad=10, loc='left')
    ax.set_xlabel('경과 시간 (분)')
    ax.set_ylabel('초당 발급 건수 (로그 눈금)')
    ax.set_yscale('log')
    handles, last = [], []
    for i, s in enumerate(STRATEGIES):
        run = data['soak'][s]['1']
        handles.append(ax.plot(run['minutes'], run['rate'], color=theme['series'][i],
                               linewidth=2, label=LABELS[s], zorder=3)[0])
        last.append((run['rate'][-1], s, theme['series'][i]))
    ax.set_xlim(0, 31)
    direct_labels(ax, last)

    ax = axes[1]
    style_axes(ax, theme)
    ax.set_title('W0 · W1 을 3회씩 — 같은 모양으로 꺾인다     실선 1회차 · 파선 2회차 · 점선 3회차',
                 fontsize=12, pad=10, loc='left')
    ax.set_xlabel('경과 시간 (분)')
    ax.set_ylabel('초당 발급 건수')
    for i, s in ((0, 'W0'), (1, 'W1')):
        for run_no, style in (('1', '-'), ('2', (0, (5, 2))), ('3', (0, (1, 1.6)))):
            run = data['soak'][s].get(run_no)
            if not run:
                continue
            ax.plot(run['minutes'], run['rate'], color=theme['series'][i], linewidth=1.8,
                    linestyle=style, label=f'{s} {run_no}회차', zorder=3)
            # 계단 하락 지점 표시
            cp = changepoint(run['rate'])
            if cp and cp['drop'] > 0.08:
                ax.plot([run['minutes'][cp['index']]], [run['rate'][cp['index']]],
                        marker='v', markersize=7, color=theme['series'][i],
                        markeredgecolor=theme['surface'], markeredgewidth=2, zorder=4)
    ax.set_xlim(0, 31)
    ax.set_ylim(0, None)

    fig.suptitle('NFR-06 — 30분 동안 성능이 유지되는가  (soak)',
                 fontsize=13.5, color=theme['ink'], x=0.008, ha='left', y=1.0)
    figure_legend(fig, theme, handles, [LABELS[s] for s in STRATEGIES], y=0.93)
    caption(fig, theme,
            'W0 과 W1 은 하락 폭이 26.5% 와 24.9% 로 사실상 같다 - 열화는 비관적 락 고유의 특성이 아니다. '
            'W2 는 3회 모두 5~10분에 꺾여 초당 12~13건으로 주저앉는다 (77~81%).\n'
            '역삼각형이 계단 하락 지점이다. 하락이 완만한 우하향이 아니라 계단이므로, 기준 구간을 고정해 '
            '앞뒤를 비교하면 늦게 온 하락을 놓친다. W3 · W4 는 1회 스크리닝에서 평평해 D-05 에 따라 1회로 마쳤다.')
    save(fig, out, theme)
    plt.close(fig)


def chart_chaos(plt, data, theme, out):
    fig, ax = plt.subplots(figsize=(8, 4.4))
    fig.patch.set_facecolor(theme['surface'])
    fig.subplots_adjust(top=0.82)
    style_axes(ax, theme)
    runs = ['1', '2', '3']
    width = 0.36
    for j, s in enumerate(['W3', 'W4']):
        color = theme['series'][STRATEGIES.index(s)]
        values = [data['chaos'][s][r] for r in runs]
        xs = [k + (j - 0.5) * width for k in range(len(runs))]
        ax.bar(xs, values, width=width - 0.02, color=color, label=LABELS[s], zorder=3)
        for x0, v in zip(xs, values):
            ax.annotate(f'{v:+,}'.replace('-', '\u2212' if False else '-'), (x0, v),
                        xytext=(0, 6 if v >= 0 else -15), textcoords='offset points',
                        ha='center', color=theme['ink'], fontsize=9.5, fontweight='bold')
    ax.axhline(0, color=theme['ink2'], linewidth=1)
    ax.set_xticks(range(len(runs)))
    ax.set_xticklabels([f'{r}회차' for r in runs])
    ax.set_ylabel('답한 수  -  DB 가 기억하는 수')
    ax.set_ylim(-6000, 68000)
    ax.set_title('NFR-05 — Redis 가 죽었을 때 무엇을 잃는가  (chaos)',
                 fontsize=12.5, pad=10, loc='left')
    ax.legend(loc='upper right', frameon=False, fontsize=9.5, labelcolor=theme['ink2'])
    caption(fig, theme,
            'W3 는 시간을 잃는다 - 정전 10초 동안 실패하고 살아난 뒤 정상 재개한다. '
            '차이가 -1 이라 막대가 보이지 않는다 (커밋 뒤 unlock 이 실패한 1건).\n'
            'W4 는 사실을 잃는다 - 큐에 있던 발급이 Redis 와 함께 사라졌다. '
            'DB 만 보면 초과 0 · 중복 0 으로 완벽하게 나온다.', y=-0.04)
    save(fig, out, theme)
    plt.close(fig)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--week', default='week2-experiment-a')
    parser.add_argument('--refresh', action='store_true', help='Prometheus 를 다시 읽는다')
    args = parser.parse_args()

    out_dir = os.path.join(ROOT, 'results', args.week, 'charts')
    os.makedirs(out_dir, exist_ok=True)
    cache = os.path.join(out_dir, 'data.json')

    if args.refresh or not os.path.exists(cache):
        print('Prometheus + raw/*.json 에서 추출하는 중...')
        data = extract(args.week)
        with open(cache, 'w', encoding='utf-8') as f:
            json.dump(data, f, ensure_ascii=False, indent=1)
        print('  ', os.path.relpath(cache, ROOT))
    else:
        with open(cache, encoding='utf-8') as f:
            data = json.load(f)
        print('캐시로 그린다 (다시 읽으려면 --refresh)')

    plt = setup_matplotlib()
    for mode, theme in THEME.items():
        chart_ramp(plt, data, theme, os.path.join(out_dir, f'ramp-comparison-{mode}.png'))
        chart_soak(plt, data, theme, os.path.join(out_dir, f'soak-timeline-{mode}.png'))
        chart_chaos(plt, data, theme, os.path.join(out_dir, f'chaos-loss-{mode}.png'))


if __name__ == '__main__':
    main()
