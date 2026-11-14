#!/usr/bin/env python3
"""Design token renderer/checker. Run --write after editing docs/design-tokens.json."""
import argparse
import ast
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DATA = json.loads((ROOT / 'docs/design-tokens.json').read_text())

def kebab(value):
    return re.sub(r'(?<=space)(?=\d)', '-', re.sub(r'([A-Z])', r'-\1', value).lower().replace('_', '-'))

def render():
    css = ['/* Generated from docs/design-tokens.json; use verify-visual-tokens.py --write. */', ':root {']
    dart = ["// BEGIN GENERATED TOKENS", 'abstract final class OaTokens {']
    def add(name, value, kind):
        css_value = value
        dart_value = json.dumps(value, ensure_ascii=False)
        dtype = 'String'
        if kind == 'color':
            raw = value.lstrip('#')
            raw = ('ff' + raw) if len(raw) == 6 else raw[6:] + raw[:6]
            dart_value, dtype = f'Color(0x{raw})', 'Color'
        elif kind == 'weight':
            dart_value, dtype = f'FontWeight.w{value}', 'FontWeight'
        elif kind == 'px':
            css_value, dart_value, dtype = f'{value}px', str(float(value)), 'double'
        elif kind == 'number':
            dart_value, dtype = str(float(value)), 'double'
        css.append(f'  --{kebab(name)}: {css_value};')
        dart.append(f'  static const {dtype} {name} = {dart_value};')
    for k,v in DATA['color'].items(): add(k,v,'color')
    for k,v in DATA['status'].items():
        for field,val in v.items():
            add('status'+''.join(p.title() for p in k.split('_'))+field.title(), val, 'string' if field=='label' else 'color')
    add('fontFamily', DATA['font']['webStack'], 'string')
    add('mobileFontFamily', DATA['font']['mobileFamily'], 'string')
    for group,kind in [('size','px'),('weight','weight'),('lineHeight','number')]:
        for k,v in DATA['font'][group].items(): add('font'+group[0].upper()+group[1:]+k.title(),v,kind)
    for k,v in DATA['space'].items(): add('space'+k,v,'px')
    for k,v in DATA['radius'].items(): add('radius'+k.title(),v,'px')
    for k,v in DATA['control'].items(): add('control'+k[0].upper()+k[1:],v,'px')
    for k,v in DATA['terms'].items(): add('term'+k.title(),v,'string')
    for k,v in DATA['shadow'].items():
        for field,val in v.items(): add('shadow'+k.title()+field.title(),val,'color' if field=='color' else 'px')
        css.append(f"  --shadow-{k}: {v['x']}px {v['y']}px {v['blur']}px {v['spread']}px {v['color']};")
    css.append('}')
    dart.append('  static const mobileFontFallback = '+json.dumps(DATA['font']['mobileStack'])+';')
    dart.extend(['}', 'const oaStatuses = <String, OaStatusColors>{'])
    for k in DATA['status']:
        name='status'+''.join(p.title() for p in k.split('_'))
        dart.append(f"  '{k.upper()}': OaStatusColors(OaTokens.{name}Fg, OaTokens.{name}Bg, OaTokens.{name}Border, OaTokens.{name}Label),")
    dart.extend(['};','// END GENERATED TOKENS'])
    return '\n'.join(css)+'\n', '\n'.join(dart)

def luminance(color):
    values=[int(color[i:i+2],16)/255 for i in (1,3,5)]
    values=[v/12.92 if v<=.04045 else ((v+.055)/1.055)**2.4 for v in values]
    return sum(a*b for a,b in zip(values,[.2126,.7152,.0722]))

def contrast(a,b):
    light,dark=sorted([luminance(a),luminance(b)],reverse=True)
    return (light+.05)/(dark+.05)

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--write',action='store_true')
    args=parser.parse_args()
    css,dart=render()
    cp,dp=ROOT/'admin/src/tokens.css',ROOT/'mobile/lib/theme.dart'
    if args.write:
        cp.write_text(css)
        original=dp.read_text()
        dp.write_text(re.sub(r'// BEGIN GENERATED TOKENS.*?// END GENERATED TOKENS',lambda _:dart,original,flags=re.S))
    errors=[]
    if cp.read_text()!=css: errors.append('tokens.css 与 JSON 不一致')
    # Ignore formatter whitespace; all generated values, labels and mappings must match.
    actual=re.search(r'// BEGIN GENERATED TOKENS.*?// END GENERATED TOKENS',dp.read_text(),re.S)
    def compact(value):
        # Preserve string contents; ignore only formatting and optional trailing commas.
        parts = re.findall(r'"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|[^\s,]', value)
        return [ast.literal_eval(p) if p.startswith(('"', "'")) else p for p in parts]
    if not actual or compact(actual[0])!=compact(dart): errors.append('theme.dart 与 JSON 不一致')
    c=DATA['color']; pairs=[]
    def pair(name,a,b,minimum=4.5): pairs.append((contrast(a,b),name,minimum))
    for fg in ['text','textSecondary','textMuted','link']:
        for bg in ['bg','surface','surfaceMuted','primarySoft']: pair(f'{fg}/{bg}',c[fg],c[bg])
    for bg in ['primary','primaryHover','primaryPressed','danger']: pair('onPrimary/'+bg,c['onPrimary'],c[bg])
    for tone in ['danger','warning','success','info']: pair(tone+'/'+tone+'Soft',c[tone],c[tone+'Soft'])
    for fg in ['sidebarText','sidebarMuted']:
        for bg in ['sidebarBg','sidebarActive','sidebarHover']: pair(f'{fg}/{bg}',c[fg],c[bg])
    for bg in ['surface','surfaceMuted','bg']: pair('borderStrong/'+bg,c['borderStrong'],c[bg],3)
    pair('focus/surface',c['focus'],c['surface'],3)
    for key,v in DATA['status'].items():
        pair('status '+key,v['fg'],v['bg'])
        pair('status border '+key,v['border'],v['bg'],3)
    for ratio,name,minimum in pairs:
        if ratio<minimum: errors.append(f'{name}: {ratio:.2f} < {minimum}')
    declared = set(re.findall(r'(--[\w-]+)\s*:', css))
    for path in (ROOT / 'admin/src').rglob('*'):
        if path.suffix not in ['.css', '.vue']: continue
        for token in re.findall(r'var\((--[\w-]+)', path.read_text()):
            if token not in declared: errors.append(f'{path.relative_to(ROOT)}: 未定义变量 {token}')
    residual=[]
    for folder,excluded in [('admin/src','tokens.css'),('mobile/lib','theme.dart')]:
        for path in (ROOT/folder).rglob('*'):
            if path.suffix not in ['.css','.vue','.ts','.dart'] or path.name==excluded: continue
            for n,line in enumerate(path.read_text().splitlines(),1):
                # All literal colors are forbidden except neutral white canvas fill.
                for match in re.finditer(r'#[0-9a-fA-F]{3,8}\b|Color\(0x[0-9a-fA-F]+\)|Colors\.(?:red|green|teal|blue|orange|amber)\w*',line):
                    if match[0].lower() not in ['#fff','#ffffff']: residual.append(f'{path.relative_to(ROOT)}:{n}: {match[0]}')
    if residual: errors.extend(residual)
    print(f'一致性: {"失败" if errors else "通过"}; 对比度组合: {len(pairs)}; 硬编码残留: {len(residual)}')
    for ratio,name,minimum in sorted(pairs)[:5]: print(f'最低组合 {name}: {ratio:.2f}:1 (要求 {minimum}:1)')
    for error in errors: print('ERROR:',error)
    raise SystemExit(bool(errors))

if __name__=='__main__': main()
