/*!
 * Hangout \u2192 Chess \u2014 "vs Computer" opponent.
 * Runs off the main thread so the board never freezes while it thinks.
 * Plain negamax + alpha-beta pruning, iterative deepening within a time
 * budget, a capture/promotion-only quiescence search to avoid the horizon
 * effect, and standard piece-square tables for evaluation. No opening book,
 * no tablebase \u2014 it just reads deep and fast. Strong enough that beating
 * it takes real, correct play; it is not literally unbeatable.
 */
importScripts('vendor/chess.min.js');
// Same UMD-shape normalization as index.html — this worker has its own
// global scope, so it needs this fix independently.
if (typeof Chess !== 'function' && typeof Chess === 'object' && typeof Chess.Chess === 'function') {
  self.Chess = Chess.Chess;
}

const PIECE_VALUES = { p: 100, n: 320, b: 330, r: 500, q: 900, k: 20000 };

const PST = {
  p: [
    0, 0, 0, 0, 0, 0, 0, 0,
    50, 50, 50, 50, 50, 50, 50, 50,
    10, 10, 20, 30, 30, 20, 10, 10,
    5, 5, 10, 25, 25, 10, 5, 5,
    0, 0, 0, 20, 20, 0, 0, 0,
    5, -5, -10, 0, 0, -10, -5, 5,
    5, 10, 10, -20, -20, 10, 10, 5,
    0, 0, 0, 0, 0, 0, 0, 0,
  ],
  n: [
    -50, -40, -30, -30, -30, -30, -40, -50,
    -40, -20, 0, 0, 0, 0, -20, -40,
    -30, 0, 10, 15, 15, 10, 0, -30,
    -30, 5, 15, 20, 20, 15, 5, -30,
    -30, 0, 15, 20, 20, 15, 0, -30,
    -30, 5, 10, 15, 15, 10, 5, -30,
    -40, -20, 0, 5, 5, 0, -20, -40,
    -50, -40, -30, -30, -30, -30, -40, -50,
  ],
  b: [
    -20, -10, -10, -10, -10, -10, -10, -20,
    -10, 0, 0, 0, 0, 0, 0, -10,
    -10, 0, 5, 10, 10, 5, 0, -10,
    -10, 5, 5, 10, 10, 5, 5, -10,
    -10, 0, 10, 10, 10, 10, 0, -10,
    -10, 10, 10, 10, 10, 10, 10, -10,
    -10, 5, 0, 0, 0, 0, 5, -10,
    -20, -10, -10, -10, -10, -10, -10, -20,
  ],
  r: [
    0, 0, 0, 0, 0, 0, 0, 0,
    5, 10, 10, 10, 10, 10, 10, 5,
    -5, 0, 0, 0, 0, 0, 0, -5,
    -5, 0, 0, 0, 0, 0, 0, -5,
    -5, 0, 0, 0, 0, 0, 0, -5,
    -5, 0, 0, 0, 0, 0, 0, -5,
    -5, 0, 0, 0, 0, 0, 0, -5,
    0, 0, 0, 5, 5, 0, 0, 0,
  ],
  q: [
    -20, -10, -10, -5, -5, -10, -10, -20,
    -10, 0, 0, 0, 0, 0, 0, -10,
    -10, 0, 5, 5, 5, 5, 0, -10,
    -5, 0, 5, 5, 5, 5, 0, -5,
    0, 0, 5, 5, 5, 5, 0, -5,
    -10, 5, 5, 5, 5, 5, 0, -10,
    -10, 0, 5, 0, 0, 0, 0, -10,
    -20, -10, -10, -5, -5, -10, -10, -20,
  ],
  k_mg: [
    -30, -40, -40, -50, -50, -40, -40, -30,
    -30, -40, -40, -50, -50, -40, -40, -30,
    -30, -40, -40, -50, -50, -40, -40, -30,
    -30, -40, -40, -50, -50, -40, -40, -30,
    -20, -30, -30, -40, -40, -30, -30, -20,
    -10, -20, -20, -20, -20, -20, -20, -10,
    20, 20, 0, 0, 0, 0, 20, 20,
    20, 30, 10, 0, 0, 10, 30, 20,
  ],
  k_eg: [
    -50, -40, -30, -20, -20, -30, -40, -50,
    -40, -20, -10, 0, 0, -10, -20, -40,
    -30, -10, 20, 30, 30, 20, -10, -30,
    -20, 0, 30, 40, 40, 30, 0, -20,
    -20, 0, 30, 40, 40, 30, 0, -20,
    -30, -10, 20, 30, 30, 20, -10, -30,
    -40, -20, -10, 0, 0, -10, -20, -40,
    -50, -40, -30, -20, -20, -30, -40, -50,
  ],
};

function squareRC(square) {
  return { row: 8 - parseInt(square[1], 10), col: square.charCodeAt(0) - 97 };
}

function pstValue(table, square, color) {
  const { row, col } = squareRC(square);
  const r = color === 'w' ? row : 7 - row;
  return table[r * 8 + col];
}

function isEndgame(board) {
  let queens = 0, minorsMajors = 0;
  for (const row of board) for (const sq of row) {
    if (!sq) continue;
    if (sq.type === 'q') queens++;
    else if (sq.type !== 'p' && sq.type !== 'k') minorsMajors++;
  }
  return queens === 0 || minorsMajors <= 4;
}

function evaluate(chess) {
  const board = chess.board();
  const endgame = isEndgame(board);
  let score = 0;
  for (let r = 0; r < 8; r++) {
    for (let c = 0; c < 8; c++) {
      const sq = board[r][c];
      if (!sq) continue;
      const square = `${'abcdefgh'[c]}${8 - r}`;
      let val = PIECE_VALUES[sq.type];
      val += sq.type === 'k' ? pstValue(endgame ? PST.k_eg : PST.k_mg, square, sq.color) : pstValue(PST[sq.type], square, sq.color);
      score += sq.color === 'w' ? val : -val;
    }
  }
  return score;
}

let nodeCount = 0;
let deadline = 0;
let timedOut = false;

function orderMoves(moves, pvSan) {
  return moves
    .map(m => {
      let s = 0;
      if (pvSan && m.san === pvSan) s += 100000;
      if (m.flags.indexOf('c') > -1 || m.flags.indexOf('e') > -1) {
        s += (PIECE_VALUES[m.captured] || 100) * 10 - PIECE_VALUES[m.piece];
      }
      if (m.flags.indexOf('p') > -1) s += 800;
      if (m.flags.indexOf('k') > -1 || m.flags.indexOf('q') > -1) s += 50;
      return { m, s };
    })
    .sort((a, b) => b.s - a.s)
    .map(x => x.m);
}

function quiescence(chess, alpha, beta, color) {
  nodeCount++;
  if ((nodeCount & 1023) === 0 && Date.now() > deadline) { timedOut = true; return alpha; }

  const standPat = color * evaluate(chess);
  if (standPat >= beta) return beta;
  if (standPat > alpha) alpha = standPat;

  const moves = chess.moves({ verbose: true }).filter(
    m => m.flags.indexOf('c') > -1 || m.flags.indexOf('e') > -1 || m.flags.indexOf('p') > -1
  );
  const ordered = orderMoves(moves, null);

  for (const m of ordered) {
    chess.move(m.san);
    const score = -quiescence(chess, -beta, -alpha, -color);
    chess.undo();
    if (timedOut) return alpha;
    if (score >= beta) return beta;
    if (score > alpha) alpha = score;
  }
  return alpha;
}

function negamax(chess, depth, alpha, beta, color) {
  nodeCount++;
  if ((nodeCount & 1023) === 0 && Date.now() > deadline) { timedOut = true; return alpha; }

  if (depth === 0) return quiescence(chess, alpha, beta, color);

  const moves = chess.moves({ verbose: true });
  if (moves.length === 0) {
    if (chess.in_checkmate()) return -100000 - depth; // losing faster is worse, winning faster is better
    return 0; // stalemate / no legal moves but not mate
  }

  const ordered = orderMoves(moves, null);
  let best = -Infinity;
  for (const m of ordered) {
    chess.move(m.san);
    const score = -negamax(chess, depth - 1, -beta, -alpha, -color);
    chess.undo();
    if (timedOut) return best === -Infinity ? alpha : best;
    if (score > best) best = score;
    if (best > alpha) alpha = best;
    if (alpha >= beta) break; // beta cutoff
  }
  return best;
}

function findBestMove(fen, timeBudgetMs, maxDepth) {
  const chess = new Chess(fen);
  const color = chess.turn() === 'w' ? 1 : -1;
  deadline = Date.now() + timeBudgetMs;
  nodeCount = 0;
  timedOut = false;

  let bestMove = null;
  let bestScore = -Infinity;
  let completedDepth = 0;

  for (let depth = 1; depth <= maxDepth; depth++) {
    const rootMoves = orderMoves(chess.moves({ verbose: true }), bestMove ? bestMove.san : null);
    let roundBest = null;
    let roundBestScore = -Infinity;
    let alpha = -Infinity;
    const beta = Infinity;

    for (const m of rootMoves) {
      chess.move(m.san);
      const score = -negamax(chess, depth - 1, -beta, -alpha, -color);
      chess.undo();
      if (timedOut) break;
      if (score > roundBestScore) { roundBestScore = score; roundBest = m; }
      if (score > alpha) alpha = score;
    }

    if (roundBest && !timedOut) {
      bestMove = roundBest;
      bestScore = roundBestScore;
      completedDepth = depth;
    } else if (roundBest && timedOut && !bestMove) {
      // Ran out of time mid-first-depth \u2014 still better than no move at all.
      bestMove = roundBest;
      bestScore = roundBestScore;
      completedDepth = depth;
    }

    if (timedOut) break;
    if (bestScore > 90000) break; // found a forced mate, no need to go deeper
  }

  return {
    from: bestMove.from,
    to: bestMove.to,
    promotion: bestMove.promotion || undefined,
    san: bestMove.san,
    depth: completedDepth,
    nodes: nodeCount,
  };
}

self.onmessage = function (e) {
  const { fen, timeBudgetMs, maxDepth, requestId } = e.data || {};
  try {
    const move = findBestMove(fen, timeBudgetMs || 1800, maxDepth || 6);
    self.postMessage({ requestId, ok: true, move });
  } catch (err) {
    self.postMessage({ requestId, ok: false, error: String((err && err.message) || err) });
  }
};
