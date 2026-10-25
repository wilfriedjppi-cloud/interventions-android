// Rend illisible le code des <script> intégrés à la page (javascript-obfuscator), sans toucher au reste.
const fs = require('fs'), O = require('javascript-obfuscator');
const [, , inp, out] = process.argv;
let h = fs.readFileSync(inp, 'utf8'), n = 0;
h = h.replace(/<script>([\s\S]*?)<\/script>/g, (m, code) => {
  if (code.trim().length < 200) return m;
  const r = O.obfuscate(code, {
    compact: true, simplify: true, target: 'browser', renameGlobals: false, identifierNamesGenerator: 'hexadecimal',
    stringArray: true, stringArrayThreshold: 0.75, stringArrayEncoding: ['base64'], stringArrayRotate: true, stringArrayShuffle: true,
    stringArrayCallsTransform: false, splitStrings: false, controlFlowFlattening: false, deadCodeInjection: false,
    selfDefending: false, debugProtection: false, transformObjectKeys: false, unicodeEscapeSequence: false, numbersToExpressions: false
  }).getObfuscatedCode();
  n++; return '<script>' + r.replace(/<\/script/gi, '<\\/script') + '</script>';
});
fs.writeFileSync(out, h);
console.log('scripts rendus illisibles :', n, '·', Math.round(h.length / 1024), 'Ko');
