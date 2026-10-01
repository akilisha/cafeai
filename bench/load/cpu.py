"""Average CPU on both machines, and the app's memory, over each run in results.csv.

Run inside a results directory:  python ../../load/cpu.py <app>
Reads <app>-server-cpu.txt and <app>-loadgen-cpu.txt (mpstat 1, UTC clocks) and,
when present, <app>-server-mem.txt (app.sh memwatch: "<epoch> <rss_kb>"). Skips
each run's first 12 s, wrk2's calibration period.
"""
import os
import csv, datetime, sys
def load(f):
    out={}
    for line in open(f):
        p=line.split()
        if len(p)>=12 and p[1]=='all':
            out[p[0]]=100-float(p[-1])
    return out
def avg(m,s,e):
    v=[m.get(datetime.datetime.fromtimestamp(t,datetime.UTC).strftime('%H:%M:%S')) for t in range(s+12,e)]
    v=[x for x in v if x is not None]
    return sum(v)/len(v) if v else float('nan')
app=sys.argv[1]
lg=load(f'{app}-loadgen-cpu.txt'); sv=load(f'{app}-server-cpu.txt')
mem={}
if os.path.exists(f'{app}-server-mem.txt'):
    for line in open(f'{app}-server-mem.txt'):
        t,kb=line.split(); mem[int(t)]=int(kb)/1024
def mem_stats(s,e):
    v=[mem[t] for t in range(s+12,e) if t in mem]
    return (sum(v)/len(v), max(v)) if v else (float('nan'), float('nan'))
for r in csv.DictReader(open('results.csv')):
    if r['app']!=app or r['test'].startswith('warmup'): continue
    s,e=int(r['start']),int(r['end'])
    m_avg,m_max=mem_stats(s,e)
    print(f"{r['test']:9} c={r['connections']:>5} req={r['requested_rps']:>6} got={float(r['achieved_rps']):>8.0f}  server_cpu={avg(sv,s,e):5.1f}%  loadgen_cpu={avg(lg,s,e):5.1f}%  rss_avg={m_avg:6.0f}MB  rss_peak={m_max:6.0f}MB")
