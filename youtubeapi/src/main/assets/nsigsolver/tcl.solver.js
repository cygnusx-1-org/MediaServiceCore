/*!
 * TCL player n-function solver.
 *
 * What it makes of a player (the preprocessed player) is cached under TclChallengeProvider's solverVersion: a change here that leaves
 * what it made before unusable changes that version too. One that leaves those working (e.g. a faster preprocess, or a pruned output
 * that answers as the whole one does) keeps it, and the players cached already keep loading.
 */
var tclSolver = (function (meriyah, astring) {
  'use strict';

  // ---- minimal generic ESTree walker ----

  // The keys holding a node's children, per node type, in the order a for-in over meriyah's nodes has them (their creation order),
  // so a walk visits what it did before, in the same order. The types are the TCL players' (10 builds, 7460dd14 to 6b3d0364); any
  // other type is walked over all its keys. A for-in over every key of every node was half of the preprocess's time (0.97 of 1.9 s on
  // a desktop; a TV is 6-50 times slower). An object method's FunctionExpression has its id last, and null: its order doesn't matter.
  const CHILD_KEYS = {
    ArrayExpression: ["elements"],
    AssignmentExpression: ["left", "right"],
    BinaryExpression: ["left", "right"],
    BlockStatement: ["body"],
    BreakStatement: ["label"],
    CallExpression: ["callee", "arguments"],
    CatchClause: ["param", "body"],
    ConditionalExpression: ["test", "consequent", "alternate"],
    ContinueStatement: ["label"],
    DoWhileStatement: ["body", "test"],
    EmptyStatement: [],
    ExpressionStatement: ["expression"],
    ForInStatement: ["body", "left", "right"],
    ForStatement: ["init", "test", "update", "body"],
    FunctionDeclaration: ["id", "params", "body"],
    FunctionExpression: ["id", "params", "body"],
    Identifier: [],
    IfStatement: ["test", "consequent", "alternate"],
    LabeledStatement: ["label", "body"],
    Literal: [],
    LogicalExpression: ["left", "right"],
    MemberExpression: ["object", "property"],
    NewExpression: ["callee", "arguments"],
    ObjectExpression: ["properties"],
    Program: ["body"],
    Property: ["key", "value"],
    ReturnStatement: ["argument"],
    SequenceExpression: ["expressions"],
    SwitchCase: ["test", "consequent"],
    SwitchStatement: ["discriminant", "cases"],
    ThisExpression: [],
    ThrowStatement: ["argument"],
    TryStatement: ["block", "handler", "finalizer"],
    UnaryExpression: ["argument"],
    UpdateExpression: ["argument"],
    VariableDeclaration: ["declarations"],
    VariableDeclarator: ["id", "init"],
  };

  function forEachChild(node, fn) {
    if (!node || typeof node !== "object") return;
    const keys = CHILD_KEYS[node.type];
    if (keys === undefined) {
      forEachChildOfAnyKey(node, fn);
      return;
    }
    for (let i = 0; i < keys.length; i++) {
      forEachNodeOf(node[keys[i]], fn);
    }
  }

  function forEachChildOfAnyKey(node, fn) {
    for (const key in node) {
      if (key === "start" || key === "end" || key === "loc" || key === "range" || key === "type") continue;
      forEachNodeOf(node[key], fn);
    }
  }

  function forEachNodeOf(value, fn) {
    if (Array.isArray(value)) {
      for (let i = 0; i < value.length; i++) {
        const item = value[i];
        if (item && typeof item.type === "string") fn(item);
      }
    } else if (value && typeof value.type === "string") {
      fn(value);
    }
  }

  // The visitors get the ancestors of the node they're called with, nearest last: one list, pushed and popped as the walk goes in and
  // out, not a copy of it made for each node. They read it then, and don't keep it.
  function walkAncestor(node, visitors) {
    const ancestors = [];
    function visit(n) {
      const fn = visitors[n.type];
      if (fn) fn(n, ancestors);
      // don't walk into throw arguments — an unreachable error path shouldn't drag in its own
      // dependency chain
      if (n.type === "ThrowStatement") return;
      ancestors.push(n);
      forEachChild(n, visit);
      ancestors.pop();
    }
    visit(node);
  }

  function findGlobalArray(parsedBaseJs) {
    let globalArrayName = "";
    let globalArrayData = null;
    let globalArrayFound = false;
    let globalArrayBody = parsedBaseJs.body[1]?.expression.callee.body.body || parsedBaseJs.body[0]?.expression.callee.object.body.body;
    for (let node of globalArrayBody) {
      if (globalArrayFound) break;
      if (node?.type === "VariableDeclaration") {
        for (let v of node.declarations) {
          if (v.init?.callee?.object?.type === "Literal") {
            globalArrayName = v.id.name;
            let splitArg = v.init?.arguments[0].value;
            globalArrayData = v.init?.callee?.object.value.split(splitArg.toString());
            globalArrayFound = true;
            break;
          }
          if (v?.init?.elements) {
            globalArrayName = v.id.name;
            let globalArrayDataValue = astring.generate(v.init);
            try {
              globalArrayData = JSON.parse(globalArrayDataValue);
            } catch (e) {
              globalArrayData = Function(`"use strict"; return ${globalArrayDataValue}`)();
            }
            globalArrayFound = true;
            break;
          }
        }
      }
    }
    return { globalArrayName, globalArrayData };
  }

  function findTopLevelLiteralIndexAssignments(body, name) {
    const results = [];
    for (const node of body) {
      if (node?.type !== "ExpressionStatement") continue;
      const expr = node.expression;
      if (expr?.type !== "AssignmentExpression" || expr.operator !== "=") continue;
      const left = expr.left;
      if (
        left?.type === "MemberExpression" &&
        left.computed &&
        left.object?.type === "Identifier" &&
        left.object.name === name &&
        left.property?.type === "Literal" &&
        expr.right?.type === "Literal"
      ) {
        results.push(node);
      }
    }
    return results;
  }

  // A per-lookup full-tree scan (findAssigningStatement below) is the other hot spot when many
  // names fall through the top-level index: this builds a name -> containing top-level statement
  // map in one pass instead, cached per body.
  const assigningStatementIndexByBody = new WeakMap();

  function collectAssignedNames(node, into) {
    if (!node || typeof node !== "object") return;
    if (node.type === "AssignmentExpression" && node.left?.type === "Identifier") {
      into.add(node.left.name);
    }
    if (node.type.includes("Function")) return;
    for (const key in node) {
      if (key === "start" || key === "end" || key === "loc" || key === "range") continue;
      const value = node[key];
      if (Array.isArray(value)) {
        for (const item of value) {
          if (item && typeof item.type === "string") collectAssignedNames(item, into);
        }
      } else if (value && typeof value.type === "string") {
        collectAssignedNames(value, into);
      }
    }
  }

  function buildAssigningStatementIndex(body) {
    const map = new Map();
    const names = new Set();
    for (const node of body) {
      if (!node) continue;
      names.clear();
      collectAssignedNames(node, names);
      for (const name of names) {
        if (!map.has(name)) map.set(name, node);
      }
    }
    return map;
  }

  function findAssigningStatement(body, funcNode) {
    let index = assigningStatementIndexByBody.get(body);
    if (!index) {
      index = buildAssigningStatementIndex(body);
      assigningStatementIndexByBody.set(body, index);
    }
    return index.get(funcNode) || null;
  }

  // findMethodByName is called once per dependency name during collection, so a per-call linear
  // scan of the (often huge) top-level body turns into O(names * bodySize). This index turns
  // repeat lookups into O(1) map reads; built once per body and cached on it.
  const topLevelIndexByBody = new WeakMap();

  function buildTopLevelIndex(body) {
    const firstReturn = new Map();
    const bareNames = new Set();
    const dotAssign = new Map();

    for (const node of body) {
      if (node?.type === "FunctionDeclaration" && node.id?.name) {
        if (!firstReturn.has(node.id.name)) firstReturn.set(node.id.name, { kind: "func", node });
        continue;
      }
      if (node?.type === "VariableDeclaration") {
        for (const v of node.declarations) {
          const name = v.id.name;
          if (v.init) {
            if (!firstReturn.has(name)) firstReturn.set(name, { kind: "var", node, v });
          } else if (!firstReturn.has(name)) {
            bareNames.add(name);
          }
        }
        continue;
      }
      if (node?.type === "ExpressionStatement") {
        const expr = node.expression;
        if (
          expr?.type === "AssignmentExpression" &&
          expr.left?.type === "MemberExpression" &&
          !expr.left.computed &&
          expr.left.object?.name &&
          expr.left.property?.name &&
          expr.right
        ) {
          const key = expr.left.object.name + "." + expr.left.property.name;
          if (!dotAssign.has(key)) dotAssign.set(key, node);
        }
        if (expr?.left?.name && expr?.right) {
          if (!firstReturn.has(expr.left.name)) firstReturn.set(expr.left.name, { kind: "expr", node });
        }
        continue;
      }
      if (node?.type === "AssignmentExpression" && node.left?.name && node.right) {
        if (!firstReturn.has(node.left.name)) firstReturn.set(node.left.name, { kind: "assign", node });
      }
    }

    return { firstReturn, bareNames, dotAssign };
  }

  function getTopLevelIndex(body) {
    let index = topLevelIndexByBody.get(body);
    if (!index) {
      index = buildTopLevelIndex(body);
      topLevelIndexByBody.set(body, index);
    }
    return index;
  }

  function findMethodByName(parsed, funcNode) {
    if (!funcNode) return null;
    const dotIndex = funcNode.indexOf(".");
    let body = parsed.body[1]?.expression.callee.body.body || parsed.body[0]?.expression.callee.object.body.body;
    const index = getTopLevelIndex(body);

    if (dotIndex !== -1) {
      return index.dotAssign.get(funcNode) || null;
    }

    const entry = index.firstReturn.get(funcNode);
    if (entry) {
      if (entry.kind === "func" || entry.kind === "expr" || entry.kind === "assign") return entry.node;

      // entry.kind === "var"
      const { node, v } = entry;
      const wasEmptyArrayLiteral = v.init.type === "ArrayExpression" && v.init.elements.length === 0;
      if (v.init.type === "ArrayExpression" && v.init.elements.every(el => el?.type === "Identifier")) {
        for (let i = 0; i < v.init.elements.length; i++) {
          v.init = findMethodByName(parsed, v.init.elements[i].name);
        }
      }
      const declNode = {
        type: "VariableDeclaration",
        kind: node.kind,
        declarations: [v],
        start: node.start,
        end: node.end,
      };

      if (wasEmptyArrayLiteral) {
        const indexAssignments = findTopLevelLiteralIndexAssignments(body, funcNode);
        if (indexAssignments.length) {
          return {
            type: "Program",
            sourceType: "script",
            body: [declNode, ...indexAssignments],
            start: node.start,
            end: indexAssignments[indexAssignments.length - 1].end,
          };
        }
      }
      return declNode;
    }

    const assigningStatement = findAssigningStatement(body, funcNode);
    if (assigningStatement) {
      return {
        type: "Program",
        sourceType: "script",
        body: [
          {
            type: "VariableDeclaration",
            kind: "var",
            declarations: [{ type: "VariableDeclarator", id: { type: "Identifier", name: funcNode }, init: null }],
          },
          assigningStatement,
        ],
        start: assigningStatement.start,
        end: assigningStatement.end,
      };
    }

    if (index.bareNames.has(funcNode)) {
      return {
        type: "VariableDeclaration",
        kind: "var",
        declarations: [{ type: "VariableDeclarator", id: { type: "Identifier", name: funcNode }, init: null }],
      };
    }

    return null;
  }

  function ensureVarDeclaration(node, code) {
    if (
      node.type === "ExpressionStatement" &&
      node.expression?.type === "AssignmentExpression" &&
      node.expression.left?.type === "Identifier"
    ) {
      return "var " + code;
    }
    return code;
  }

  // Groups every prototype-method assignment by constructor name in one pass, instead of
  // rescanning the whole body per dependency (the dominant cost otherwise).
  function buildProtoMethodsIndex(parsed, globalArrayData, globalArrayName) {
    const index = new Map();
    const addTo = (ctorName, node) => {
      const list = index.get(ctorName);
      if (list) list.push(node);
      else index.set(ctorName, [node]);
    };

    const body = parsed.body[1]?.expression.callee.body.body || parsed.body[0]?.expression.callee.object.body.body;
    const aliasMap = new Map();

    function protoTargetOf(node) {
      if (!node || node.type !== "MemberExpression") return null;
      if (!node.computed) {
        if (node.property?.name === "prototype" || node.property?.value === "prototype") {
          return astring.generate(node.object).trim();
        }
        return null;
      }
      if (globalArrayData && globalArrayName) {
        const protoExpr = node.property;
        if (
          protoExpr?.type === "MemberExpression" &&
          protoExpr.object?.name === globalArrayName &&
          protoExpr.property?.type === "Literal" &&
          globalArrayData[protoExpr.property.value] === "prototype"
        ) {
          return astring.generate(node.object).trim();
        }
      }
      return null;
    }

    for (const node of body) {
      if (node.type === "VariableDeclaration") {
        for (const decl of node.declarations) {
          if (decl.id?.type === "Identifier" && decl.init) {
            const target = protoTargetOf(decl.init);
            if (target) aliasMap.set(decl.id.name, target);
          }
        }
      }
      if (
        node.type === "ExpressionStatement" &&
        node.expression?.type === "AssignmentExpression" &&
        node.expression.left?.type === "Identifier"
      ) {
        const target = protoTargetOf(node.expression.right);
        if (target) aliasMap.set(node.expression.left.name, target);
      }

      if (node.type !== "ExpressionStatement") continue;
      const expr = node.expression;
      if (expr?.type !== "AssignmentExpression") continue;
      const left = expr.left;
      if (left.type !== "MemberExpression") continue;

      if (!left.computed && left.object?.type === "Identifier") {
        const aliasTarget = aliasMap.get(left.object.name);
        if (aliasTarget) {
          const rewritten = JSON.parse(JSON.stringify(node));
          rewritten.expression.left.object = meriyah.parse(aliasTarget + ".prototype").body[0].expression;
          addTo(aliasTarget, rewritten);
          continue;
        }
      }

      const obj = left.object;
      if (obj.type !== "MemberExpression") continue;

      if (left.computed && obj.computed && globalArrayData && globalArrayName) {
        const protoExpr = obj.property;
        if (
          protoExpr?.type === "MemberExpression" &&
          protoExpr.object?.name === globalArrayName &&
          protoExpr.property?.type === "Literal"
        ) {
          const protoIdx = protoExpr.property.value;
          if (globalArrayData[protoIdx] === "prototype") {
            addTo(astring.generate(obj.object).trim(), node);
            continue;
          }
        }
      }

      if (!obj.computed && (obj.property?.name === "prototype" || obj.property?.value === "prototype")) {
        addTo(astring.generate(obj.object).trim(), node);
      }
    }

    return index;
  }

  const excludeArrayMethodsName = [
    "await","break","case","catch","class","const","continue","debugger","default",
    "delete","do","else","enum","export","extends","false","finally","for","function",
    "if","implements","import","in","instanceof","interface","let","new","null","package",
    "private","protected","public","return","static","super","switch","this","throw",
    "true","try","typeof","var","void","while","with","yield",
    "eval","isFinite","isNaN","parseFloat","parseInt","decodeURI","decodeURIComponent",
    "encodeURI","encodeURIComponent",
    "Object","Function","Boolean","Symbol","Error","EvalError","InternalError","RangeError",
    "ReferenceError","SyntaxError","TypeError","URIError","Number","BigInt","Math",
    "Date","String","RegExp","Array","Map","Set","WeakMap","WeakSet","ArrayBuffer",
    "SharedArrayBuffer","DataView","JSON","Promise","Generator","GeneratorFunction",
    "AsyncFunction","Reflect","Proxy","Intl","globalThis",
    "Buffer","process","global","__dirname","__filename","module","exports","require"
  ];

  function getUndeclaredMethods(funcCode, nFunctionName) {
    let declared = new Set();
    let unDeclared = new Set();

    function addDeclaredFromParams(params) {
      for (const p of params) {
        if (p.type === "Identifier") declared.add(p.name);
      }
    }

    walkAncestor(funcCode, {
      VariableDeclarator(node) {
        declared.add(node.id.name);
      },
      FunctionDeclaration(node) {
        declared.add(node.id.name);
        addDeclaredFromParams(node.params);
      },
      FunctionExpression(node) {
        addDeclaredFromParams(node.params);
      },
      ArrowFunctionExpression(node) {
        addDeclaredFromParams(node.params);
      },
      MemberExpression(node) {
        if (!node.computed && node.object.type === "Identifier") {
          const fullName = node.object.name + "." + node.property.name;
          if (node.object.name !== nFunctionName) unDeclared.add(fullName);
        }
      },
      Identifier(node, ancestors) {
        if (!ancestors || ancestors.length === 0) return;
        const parent = ancestors[ancestors.length - 1];
        if (node.name === nFunctionName) return;
        if (parent.type === "MemberExpression" && parent.property === node && !parent.computed) return;
        if (parent.type === "VariableDeclarator" && parent.id === node) return;
        if ((parent.type === "FunctionDeclaration" || parent.type === "FunctionExpression") && parent.id === node) return;
        if (parent.type === "Property" && parent.key === node && !parent.computed && !parent.shorthand) return;
        if (parent.type === "LabeledStatement" && parent.label === node) return;
        if (parent.type === "BreakStatement" || parent.type === "ContinueStatement") return;
        unDeclared.add(node.name);
      },
    });
    unDeclared = new Set([...unDeclared].filter(name => !excludeArrayMethodsName.includes(name)));
    declared = new Set([...declared].filter(name => !excludeArrayMethodsName.includes(name)));

    return new Set([...unDeclared].filter(name => !declared.has(name)));
  }

  // ownNames may be a single name or an array, so multiple entry points can share one visited set.
  // protoMethodsByCtorName: the player's, made once (see buildProtoMethodsIndex, preprocessPlayer)
  // bodies: the extract's, its pieces' function bodies added to it (see makePiece)
  // Returns each dependency's pieces, in the order they're emitted: a declaration's one, or a constructor's prototype methods'.
  function collectDependencies(entryNodes, ownNames, parsedBaseJs, protoMethodsByCtorName, bodies) {
    const ownNameSet = new Set(Array.isArray(ownNames) ? ownNames : [ownNames]);
    const visitedNames = new Set();
    const nameToCodeText = new Map();
    const nameToDepNames = new Map();

    // What getUndeclaredMethods gives for each own name, merged in the order of the own names: from one walk of the node, not one per
    // own name (two walks of every dependency, a third of the preprocess's time). An own name only leaves out itself and its members
    // ("own.x"), and where a name first comes in the walk doesn't depend on it: each one's list is the walk's, less those.
    function undeclaredExcludingOwnNames(node) {
      const all = getUndeclaredMethods(node, null);
      const merged = new Set();
      for (const ownName of ownNameSet) {
        for (const dep of all) {
          if (!isOfOwnName(dep, ownName)) merged.add(dep);
        }
      }
      return new Set([...merged].filter(dep => !ownNameSet.has(dep)));
    }

    // The name itself, or a member of it ("name.x"): what getUndeclaredMethods leaves out for it
    function isOfOwnName(dep, ownName) {
      if (dep === ownName) return true;
      const dot = dep.indexOf(".");
      return dot !== -1 && dep.slice(0, dot) === ownName;
    }

    function resolveAndCollect(name) {
      if (ownNameSet.has(name) || visitedNames.has(name)) return;
      visitedNames.add(name);

      const declarationNode = findMethodByName(parsedBaseJs, name);
      if (!declarationNode) return;

      // Generated now, before the names it depends on are looked up (a lookup may change the tree, see findMethodByName)
      nameToCodeText.set(name, [makePiece(declarationNode, generateCode, bodies)]);
      const depNames = undeclaredExcludingOwnNames(declarationNode);

      const protoMethodNodes = protoMethodsByCtorName.get(name.trim()) || [];
      if (protoMethodNodes.length) {
        const protoGroupKey = name + "$$proto";
        nameToCodeText.set(protoGroupKey, protoMethodNodes.map(node => makePiece(node, generateCode, bodies)));

        const protoDepNames = new Set();
        for (const protoMethodNode of protoMethodNodes) {
          for (const dep of undeclaredExcludingOwnNames(protoMethodNode)) protoDepNames.add(dep);
        }
        for (const dep of protoDepNames) depNames.add(dep);
        // proto methods depend on the constructor itself
        nameToDepNames.set(protoGroupKey, new Set([...protoDepNames, name]));
      }

      nameToDepNames.set(name, depNames);

      for (const dep of depNames) resolveAndCollect(dep);
    }

    const rootDepNames = new Set();
    for (const entryNode of Array.isArray(entryNodes) ? entryNodes : [entryNodes]) {
      for (const dep of undeclaredExcludingOwnNames(entryNode)) rootDepNames.add(dep);
    }
    for (const name of rootDepNames) resolveAndCollect(name);

    const orderedNames = [];
    const topoVisited = new Set();

    function topoSort(name) {
      if (topoVisited.has(name)) return;
      topoVisited.add(name);
      for (const dep of (nameToDepNames.get(name) || [])) topoSort(dep);
      if (nameToCodeText.has(name)) orderedNames.push(name);
    }

    for (const name of nameToCodeText.keys()) topoSort(name);

    return orderedNames.map(name => nameToCodeText.get(name));
  }

  function generateCode(node) {
    const raw = astring.generate(node).replace(/;;+\s*$/, ";");
    return ensureVarDeclaration(node, raw);
  }

  // ---- the preprocessed player's pruning ----

  // In a piece's skeleton, where the body of its function number n goes: "__tclBody<n>__"
  const BODY_MARK = "__tclBody";
  const BODY_MARK_PATTERN = /__tclBody(\d+)__/g;
  // Of the trace of the top-level functions run (see pruneExtract): a global the traced extract marks them in
  const USED_GLOBAL = "__tclUsed";
  // Thrown by a function pruned, should it run after all: TclChallengeProvider preprocesses the player again, without pruning
  const PRUNED_MARK = "tcl-pruned";
  // Set by a function pruned as it throws, a global: the throw may be caught in the player, and the n it gives wrong. The solve
  // looks at it after the n-function ran (see TclChallengeProvider, solveFunctionScript), and so does the check of the pruned one.
  const PRUNED_GLOBAL = "tclPrunedReached";
  // Of the pruned extract: what its pruned functions call to set PRUNED_GLOBAL and throw. One function, as each of thousands of them
  // setting it in its own body made the extract a third bigger. Under BODY_MARK, so a player that can be pruned has no such name.
  const PRUNED_FUNCTION = BODY_MARK + "Pruned";
  // The url TclChallengeProvider wraps an n-parameter in to solve it (see solveN)
  const CHALLENGE_URL = "https://www.youtube.com/videoplayback?n=";
  const N_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

  // The functions of a declaration that are at its top: a function declaration, "var x = function…", "a.b = function…", or those of a
  // synthetic Program's statements (see findMethodByName)
  function topLevelFunctionsOf(node) {
    if (!node) return [];
    if (node.type === "FunctionDeclaration") return [node];
    if (node.type === "VariableDeclaration") {
      return node.declarations.filter(d => d.init?.type === "FunctionExpression").map(d => d.init);
    }
    if (node.type === "ExpressionStatement") {
      const expr = node.expression;
      return expr?.type === "AssignmentExpression" && expr.right?.type === "FunctionExpression" ? [expr.right] : [];
    }
    if (node.type === "Program") return node.body.flatMap(topLevelFunctionsOf);
    return [];
  }

  // A declaration of the extract, generated as before (full), and again with the bodies of its top-level functions left out
  // (skeleton): each one's text goes to bodies, and its number into the skeleton, where it goes. Both made now, from the tree as it is
  // (see collectDependencies). A declaration with no such function is its text.
  function makePiece(node, generate, bodies) {
    const full = generate(node);
    const functions = topLevelFunctionsOf(node);
    if (!functions.length) return full;

    const saved = functions.map(fn => fn.body);
    let skeleton;
    try {
      functions.forEach((fn, i) => {
        fn.body = { type: "Identifier", name: BODY_MARK + (bodies.length + i) + "__" };
      });
      skeleton = generate(node);
    } finally {
      functions.forEach((fn, i) => {
        fn.body = saved[i];
      });
    }
    for (const fn of functions) bodies.push(astring.generate(fn.body));

    return { full, skeleton };
  }

  function pieceText(piece, bodyOf) {
    if (typeof piece === "string") return piece;
    return bodyOf ? piece.skeleton.replace(BODY_MARK_PATTERN, (mark, n) => bodyOf(Number(n))) : piece.full;
  }

  function randomChallenge(length) {
    let n = "";
    for (let i = 0; i < length; i++) n += N_ALPHABET[Math.floor(Math.random() * N_ALPHABET.length)];
    return CHALLENGE_URL + n;
  }

  // The preprocessed player with the bodies of the top-level functions the n-function doesn't run left out: a fraction of it to compile,
  // and to run at each solve (TclChallengeProvider makes the n-function anew for each). 155 of 6704 functions ran (2.48 MB to 0.68 MB) on
  // 6b3d0364, and which ones doesn't depend on the n: on 9 builds the first of 5000 random ones ran all that the 5000 did. So a few find
  // them, and the pruned one must answer as the whole one does for others, or the whole one is kept.
  function pruneExtract(extract, nFunction) {
    if (!extract.canPrune || extract.bodies.length === 0) return extract.full;

    try {
      const used = new Uint8Array(extract.bodies.length);
      globalThis[USED_GLOBAL] = used;
      try {
        const traced = new Function(extract.render(n => "{" + USED_GLOBAL + "[" + n + "]=1;" + extract.bodies[n].slice(1)))();
        for (let i = 0; i < 8; i++) traced(randomChallenge(4 + i * 4));
      } finally {
        delete globalThis[USED_GLOBAL];
      }

      const stub = "{throw " + PRUNED_FUNCTION + "();}";
      const pruned = "function " + PRUNED_FUNCTION + "() { globalThis." + PRUNED_GLOBAL + " = 1; return \"" + PRUNED_MARK + "\"; }\n" +
        extract.render(n => used[n] ? extract.bodies[n] : stub);
      try {
        const prunedFunction = new Function(pruned)();
        for (let i = 0; i < 16; i++) {
          const challenge = randomChallenge(2 + i * 2);
          if (prunedFunction(challenge) !== nFunction(challenge)) return extract.full;
        }
        // A pruned one ran, its throw caught in the player, and the n's came out the same all the same
        if (globalThis[PRUNED_GLOBAL]) return extract.full;
      } finally {
        delete globalThis[PRUNED_GLOBAL];
      }

      return pruned;
    } catch (e) {
      return extract.full;
    }
  }

  // Matches "obj[expr[literal]]" — a computed member access whose own index is itself computed
  // (e.g. "O[r[65]]", the scrambled-property-lookup idiom guarding the n-function call).
  function isNestedComputedMember(node) {
    return node?.type === "MemberExpression" && node.computed &&
      node.property?.type === "MemberExpression" && node.property.computed;
  }

  // Finds the n-function call site, e.g. "...&&O[r[65]]&&(w=m$(2,4027,O));" — a "&&" guard
  // ending in a scrambled property read, then an assignment of a 3-arg call (two int literals
  // + the URL-param object). Ambiguous on its own — unrelated helpers can share this exact
  // shape — so it is only a fallback; preprocessPlayer prefers findNCallSiteByThrow below.
  // Old regex (kept for reference): /\]\]&&\([A-z0-9$]+\=([A-z0-9$]+)\(([0-9]+),([0-9]+),[A-z0-9$]+\)\);(?:\)|)/
  function findNCallSite(parsedBaseJs) {
    let best = null;
    function visit(node) {
      if (
        node.type === "LogicalExpression" && node.operator === "&&" &&
        (isNestedComputedMember(node.left) || (node.left.type === "LogicalExpression" && isNestedComputedMember(node.left.right)))
      ) {
        const assignment = node.right;
        if (assignment?.type === "AssignmentExpression" && assignment.operator === "=") {
          const call = assignment.right;
          if (call?.type === "CallExpression" && call.callee?.type === "Identifier" && call.arguments.length === 3) {
            const [arg1, arg2, arg3] = call.arguments;
            if (
              arg1?.type === "Literal" && typeof arg1.value === "number" &&
              arg2?.type === "Literal" && typeof arg2.value === "number" &&
              arg3?.type === "Identifier" &&
              (!best || node.start < best.start)
            ) {
              best = { functionName: call.callee.name, args: arg1.value + "," + arg2.value, start: node.start };
            }
          }
        }
      }
      forEachChild(node, visit);
    }
    visit(parsedBaseJs);
    return best;
  }

  // The real n-transform throws on failure, unlike unrelated helpers that can match the
  // "&&"-guarded 3-arg-call shape above by coincidence. This confirms the call site by finding
  // that throw directly, then re-derives its call args from a plain invocation elsewhere in the
  // code, rather than trusting whatever "&&" shape happened to match first.
  //   throw pattern: /throw new [A-z0-9$]+\([A-z]\[/   ([A-z] = the global array name)
  //   call pattern:  /FF\([0-9]+,[0-9]+,[A-z0-9$]+\);/  ("FF" = the confirmed function's name)
  function findNCallSiteByThrow(parsedBaseJs, globalArrayName) {
    if (!globalArrayName) return null;

    // Only a throw that is a *direct* descendant of `funcNode` counts — one nested inside an
    // inner function doesn't implicate the outer one. Deliberately does not reuse walkAncestor,
    // which skips into ThrowStatement's own children for dependency-collection purposes.
    function throwsFromGlobalArrayDirect(funcNode) {
      let found = false;
      (function visit(node) {
        if (found || !node || typeof node !== "object") return;
        if (node.type?.includes("Function") && node !== funcNode) return;
        if (node.type === "ThrowStatement") {
          const arg = node.argument;
          if (arg?.type === "NewExpression" && arg.callee?.type === "Identifier") {
            for (const a of arg.arguments || []) {
              if (
                a.type === "MemberExpression" && a.computed &&
                a.object?.type === "Identifier" && a.object.name === globalArrayName
              ) {
                found = true;
                return;
              }
            }
          }
        }
        forEachChild(node, visit);
      })(funcNode.body);
      return found;
    }

    // A bare call `funcName(a, b, ident)` anywhere — literal, literal, identifier args —
    // regardless of whether it sits in an expression statement or feeds a `var`/assignment.
    function findCallArgs(funcName) {
      let result = null;
      function visit(node) {
        if (result || !node || typeof node !== "object") return;
        if (
          node.type === "CallExpression" &&
          node.callee?.type === "Identifier" && node.callee.name === funcName &&
          node.arguments.length === 3
        ) {
          const [a0, a1, a2] = node.arguments;
          if (
            a0.type === "Literal" && typeof a0.value === "number" &&
            a1.type === "Literal" && typeof a1.value === "number" &&
            a2.type === "Identifier"
          ) {
            result = a0.value + "," + a1.value;
            return;
          }
        }
        forEachChild(node, visit);
      }
      visit(parsedBaseJs);
      return result;
    }

    // Earliest confirming function by source position.
    // The walk goes in source order but for a for-in's body, which meriyah puts before its left and right (see CHILD_KEYS), and a
    // function comes before the ones in it: the first one confirmed is the earliest, and nothing after it is looked at. The nodes have
    // no positions (the player is parsed without ranges, which doubled the heap): the check of one by its position never stopped the
    // walk, and it went on through the whole player, keeping the last one confirmed (each of 10 builds has only one).
    let confirmed = null;
    function visit(node) {
      if (confirmed) return;
      if (node.type === "VariableDeclarator" && node.id?.type === "Identifier" && node.init?.type === "FunctionExpression") {
        if (throwsFromGlobalArrayDirect(node.init)) {
          confirmed = { functionName: node.id.name, start: node.start };
        }
      } else if (node.type === "FunctionDeclaration" && node.id?.name) {
        if (throwsFromGlobalArrayDirect(node)) {
          confirmed = { functionName: node.id.name, start: node.start };
        }
      }
      forEachChild(node, visit);
    }
    visit(parsedBaseJs);

    if (!confirmed) return null;
    const args = findCallArgs(confirmed.functionName);
    if (!args) return null;
    return { functionName: confirmed.functionName, args, start: confirmed.start };
  }

  // A bare identifier / "this" / property-chain off either — excludes literals, so a generic
  // "new Error('message')" doesn't get mistaken for the URL-builder.
  function isThreadedArg(node) {
    if (node?.type === "Identifier" || node?.type === "ThisExpression") return true;
    if (node?.type === "MemberExpression") return isThreadedArg(node.object);
    return false;
  }

  // Does this statement start with identifier `name`, as in "X..." right after "var X=new Y(...)"?
  function statementStartsWithIdentifier(node, name) {
    if (node?.type !== "ExpressionStatement") return false;
    let expr = node.expression;
    while (expr) {
      if (expr.type === "AssignmentExpression") { expr = expr.left; continue; }
      if (expr.type === "CallExpression") { expr = expr.callee; continue; }
      if (expr.type === "MemberExpression") { expr = expr.object; continue; }
      break;
    }
    return expr?.type === "Identifier" && expr.name === name;
  }

  // Finds the URL-param object's constructor: a two-arg "new" call assigned to a fresh var,
  // immediately reused by the next statement — e.g. "var B=new J5(this[y[163]],this[y[9]]);B[y[1826]]=...".
  // Old regex (kept for reference): /;var ([A-z0-9$]+)=new ([A-z0-9$]+)\([A-z0-9]+,[A-z0-9]+\);\1/
  function findUrlBuilderCtor(parsedBaseJs) {
    let best = null;
    function visitBody(body) {
      for (let i = 0; i < body.length - 1; i++) {
        const node = body[i];
        if (node?.type !== "VariableDeclaration" || node.declarations.length !== 1) continue;
        const decl = node.declarations[0];
        if (decl.id?.type !== "Identifier" || decl.init?.type !== "NewExpression") continue;
        if (decl.init.callee?.type !== "Identifier" || decl.init.arguments.length !== 2) continue;
        if (!decl.init.arguments.every(isThreadedArg)) continue;
        if (statementStartsWithIdentifier(body[i + 1], decl.id.name) && (!best || node.start < best.start)) {
          best = { ctorName: decl.init.callee.name, start: node.start };
        }
      }
    }
    // The nodes have no positions (see findNCallSiteByThrow), so the first one found is kept (best.start is undefined, and so is the
    // check against it): nothing after it is looked at.
    function visit(node) {
      if (best) return;
      if ((node.type === "Program" || node.type === "BlockStatement") && Array.isArray(node.body)) {
        visitBody(node.body);
      }
      forEachChild(node, visit);
    }
    visit(parsedBaseJs);
    return best;
  }

  function preprocessPlayer(baseJsData) {
    let parsedBaseJs = meriyah.parse(baseJsData);

    let globalArray = findGlobalArray(parsedBaseJs);

    const nCallSite = findNCallSiteByThrow(parsedBaseJs, globalArray.globalArrayName) || findNCallSite(parsedBaseJs);
    if (!nCallSite) throw new Error("Could not locate TCL n-function call site");

    let nFunctionName = nCallSite.functionName;
    let nCallArgs = nCallSite.args;
    let nFunctionNode = findMethodByName(parsedBaseJs, nFunctionName);
    if (!nFunctionNode) throw new Error("Could not locate n-function body for " + nFunctionName);

    const urlBuilderCtor = findUrlBuilderCtor(parsedBaseJs);
    if (!urlBuilderCtor) throw new Error("Could not locate URL-builder constructor");
    let urlBuilderCtorName = urlBuilderCtor.ctorName;

    // The function bodies of the extract's pieces, by number (see makePiece)
    const bodies = [];
    const urlBuilderCtorNode = findMethodByName(parsedBaseJs, urlBuilderCtorName);
    const urlBuilderCtorPiece = urlBuilderCtorNode ? makePiece(urlBuilderCtorNode, generateCode, bodies) : "";

    // Made once: the dependencies' walk takes it too (it was made again there, a pass over the player's body)
    const protoMethodsByCtorName = buildProtoMethodsIndex(parsedBaseJs, globalArray.globalArrayData, globalArray.globalArrayName);
    const urlBuilderProtoMethodNodes = protoMethodsByCtorName.get(urlBuilderCtorName.trim()) || [];
    const urlBuilderProtoMethodPieces = urlBuilderProtoMethodNodes.map(node => makePiece(node, generateCode, bodies));

    // Shared dep walk avoids emitting deps common to both entry points twice.
    const sharedDeps = collectDependencies(
      [nFunctionNode, ...(urlBuilderCtorNode ? [urlBuilderCtorNode] : []), ...urlBuilderProtoMethodNodes],
      [nFunctionName, urlBuilderCtorName],
      parsedBaseJs,
      protoMethodsByCtorName,
      bodies
    );
    const nFunctionPiece = makePiece(nFunctionNode, astring.generate, bodies);

    // "g" is a namespace object some builds attach methods to (e.g. "g.Foo = function(){}");
    // harmless to predeclare even when unused.
    const globalArrayDecl = `var ${globalArray.globalArrayName} = ${JSON.stringify(globalArray.globalArrayData)};`;
    let urlParamsInit = "let urlParams = new " + urlBuilderCtorName + "(nValue, true); \n\n";

    // The extract, with each top-level function's body the one bodyOf gives for its number (see makePiece); as generated without one
    function render(bodyOf) {
      const text = piece => pieceText(piece, bodyOf);
      let combinedSection =
        `var g = {};\n${globalArrayDecl}\n${text(nFunctionPiece)}\n` +
        sharedDeps.map(group => group.map(text).join("\n")).join("\n") + "\n" + text(urlBuilderCtorPiece) + "\n" +
        urlBuilderProtoMethodPieces.map(text).join("\n");

      return (
        "if (typeof globalThis.XMLHttpRequest === 'undefined') { globalThis.XMLHttpRequest = { prototype: {} }; }\n" +
        "if (typeof location !== 'undefined') { try { location.href = 'https://www.youtube.com/watch?v=yt-dlp-wins'; } catch (e) {} }\n" +
        combinedSection +
        "\nvar nFunction=function(nValue) { " + urlParamsInit + "; " + nFunctionName + "(" + nCallArgs + ", urlParams); return urlParams['get']('n') };" +
        "\nreturn nFunction;"
      );
    }

    // A player that has the mark itself can't be pruned: the skeletons' marks wouldn't be told from its own
    return { full: render(null), render, bodies, canPrune: baseJsData.indexOf(BODY_MARK) === -1 };
  }

  // Entry point matching the calling convention used by TclChallengeProvider:
  // input = { type: "player", player: "<base.js source>", challenges: [...], output_preprocessed: true, prune?: false }
  //       | { type: "preprocessed", preprocessed_player: "<cached script from a prior result>", challenges: [...] }
  // output = { type: "result", results: { "<challenge>": "<decoded n>" }, preprocessed_player?: "..." }
  //        | { type: "error", error: "..." }
  // The preprocessed player given is pruned (see pruneExtract), unless prune is false.
  function main(input) {
    try {
      const extract = input.type === "preprocessed" ? null : preprocessPlayer(input.player);
      const preprocessedPlayer = extract ? extract.full : input.preprocessed_player;
      const nFunction = new Function(preprocessedPlayer)();
      const results = {};
      for (const challenge of input.challenges) {
        results[challenge] = nFunction(challenge);
      }
      const output = { type: "result", results: results };
      if (input.type === "player" && input.output_preprocessed) {
        output.preprocessed_player = input.prune === false ? preprocessedPlayer : pruneExtract(extract, nFunction);
      }
      return output;
    } catch (error) {
      return {
        type: "error",
        error: error instanceof Error ? `${error.message}\n${error.stack}` : `${error}`,
      };
    }
  }

  return main;
})(meriyah, astring);
