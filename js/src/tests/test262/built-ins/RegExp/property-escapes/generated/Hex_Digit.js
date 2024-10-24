// Copyright 2024 Mathias Bynens. All rights reserved.
// This code is governed by the BSD license found in the LICENSE file.

/*---
author: Mathias Bynens
description: >
  Unicode property escapes for `Hex_Digit`
info: |
  Generated by https://github.com/mathiasbynens/unicode-property-escapes-tests
  Unicode v16.0.0
esid: sec-static-semantics-unicodematchproperty-p
features: [regexp-unicode-property-escapes]
includes: [regExpUtils.js]
---*/

const matchSymbols = buildString({
  loneCodePoints: [],
  ranges: [
    [0x000030, 0x000039],
    [0x000041, 0x000046],
    [0x000061, 0x000066],
    [0x00FF10, 0x00FF19],
    [0x00FF21, 0x00FF26],
    [0x00FF41, 0x00FF46]
  ]
});
testPropertyEscapes(
  /^\p{Hex_Digit}+$/u,
  matchSymbols,
  "\\p{Hex_Digit}"
);
testPropertyEscapes(
  /^\p{Hex}+$/u,
  matchSymbols,
  "\\p{Hex}"
);

const nonMatchSymbols = buildString({
  loneCodePoints: [],
  ranges: [
    [0x00DC00, 0x00DFFF],
    [0x000000, 0x00002F],
    [0x00003A, 0x000040],
    [0x000047, 0x000060],
    [0x000067, 0x00DBFF],
    [0x00E000, 0x00FF0F],
    [0x00FF1A, 0x00FF20],
    [0x00FF27, 0x00FF40],
    [0x00FF47, 0x10FFFF]
  ]
});
testPropertyEscapes(
  /^\P{Hex_Digit}+$/u,
  nonMatchSymbols,
  "\\P{Hex_Digit}"
);
testPropertyEscapes(
  /^\P{Hex}+$/u,
  nonMatchSymbols,
  "\\P{Hex}"
);

reportCompare(0, 0);
