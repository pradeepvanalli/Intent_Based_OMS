/* Minimal YAML tokenizer for syntax highlighting. Not a parser; works line by line. */
export type Tok = { cls: string; text: string };
export function tokenizeYamlLine(line: string): Tok[] {
  const out: Tok[] = [];
  let rest = line;
  const indent = /^\s*/.exec(rest)?.[0] ?? '';
  if (indent) { out.push({ cls: '', text: indent }); rest = rest.slice(indent.length); }
  if (rest.startsWith('#')) { out.push({ cls: 'tok-cmt', text: rest }); return out; }
  if (rest.startsWith('- ')) { out.push({ cls: 'tok-punc', text: '- ' }); rest = rest.slice(2); }
  else if (rest === '-') { out.push({ cls: 'tok-punc', text: '-' }); return out; }
  const kv = /^([^:#"'{[]+?)(:)(\s|$)/.exec(rest);
  if (kv) { out.push({ cls: 'tok-key', text: kv[1] }); out.push({ cls: 'tok-punc', text: ':' }); rest = rest.slice(kv[1].length + 1); if (kv[3]) { out.push({ cls: '', text: kv[3] }); rest = rest.slice(kv[3].length); } }
  pushValue(rest, out);
  return out;
}
function pushValue(rest: string, out: Tok[]) {
  let i = 0;
  while (i < rest.length) {
    const ch = rest[i];
    if (ch === '#') { out.push({ cls: 'tok-cmt', text: rest.slice(i) }); return; }
    if (ch === '"' || ch === "'") { const end = rest.indexOf(ch, i + 1); const e = end === -1 ? rest.length : end + 1; out.push({ cls: 'tok-str', text: rest.slice(i, e) }); i = e; continue; }
    if ('{}[],'.includes(ch)) { out.push({ cls: 'tok-punc', text: ch }); i++; continue; }
    if (ch === ' ') { out.push({ cls: '', text: ' ' }); i++; continue; }
    // inside flow mappings: key: value
    const m = /^([A-Za-z_][\w.-]*)(:)(\s)/.exec(rest.slice(i));
    if (m) { out.push({ cls: 'tok-key', text: m[1] }); out.push({ cls: 'tok-punc', text: ':' }); out.push({ cls: '', text: m[3] }); i += m[0].length; continue; }
    const w = /^[^\s{}[\],#"']+/.exec(rest.slice(i))?.[0] ?? ch;
    const cls = /^(true|false|null|~)$/.test(w) ? 'tok-bool' : /^-?\d+(\.\d+)?$/.test(w) || /^P(T|\d)/.test(w) ? 'tok-num' : 'tok-str';
    out.push({ cls, text: w }); i += w.length;
  }
}
