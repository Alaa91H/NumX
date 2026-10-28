"""Small regular-expression to DFA compiler for libphonenumber numbering plans.

The numbering metadata uses a deliberately small regular-expression dialect:
digits, character classes, non-capturing groups, alternation, ``?`` and bounded
repetition ``{m,n}``.  Compiling the patterns once, at build time, gives the app
an exact finite automaton that can count and enumerate every allocated number
without materialising huge ranges or relying on regular-expression backtracking
at runtime.

The generated automaton is converted to a product with a bounded length counter.
As a result every state belongs to exactly one national-number length and the
state graph is acyclic.  That property makes counting and ordered enumeration
both simple and fast.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Dict, FrozenSet, Iterable, List, Optional, Sequence, Set, Tuple

DIGITS = tuple("0123456789")


class PatternError(ValueError):
    pass


# ---------------------------------------------------------------------------
# Parser
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class CharSet:
    digits: FrozenSet[str]


@dataclass(frozen=True)
class SequenceNode:
    parts: Tuple["Node", ...]


@dataclass(frozen=True)
class AlternationNode:
    options: Tuple["Node", ...]


@dataclass(frozen=True)
class RepeatNode:
    node: "Node"
    minimum: int
    maximum: Optional[int]


Node = object


class Parser:
    def __init__(self, pattern: str):
        self.pattern = "".join(pattern.split())
        self.index = 0

    def parse(self) -> Node:
        node = self.parse_alternation()
        if self.index != len(self.pattern):
            raise PatternError(
                f"unexpected {self.pattern[self.index]!r} at {self.index} in {self.pattern!r}"
            )
        return node

    def peek(self) -> Optional[str]:
        return self.pattern[self.index] if self.index < len(self.pattern) else None

    def take(self) -> str:
        if self.index >= len(self.pattern):
            raise PatternError("unexpected end of pattern")
        ch = self.pattern[self.index]
        self.index += 1
        return ch

    def parse_alternation(self) -> Node:
        options = [self.parse_sequence()]
        while self.peek() == "|":
            self.take()
            options.append(self.parse_sequence())
        if len(options) == 1:
            return options[0]
        return AlternationNode(tuple(options))

    def parse_sequence(self) -> Node:
        parts: List[Node] = []
        while self.peek() is not None and self.peek() not in ")|":
            parts.append(self.parse_repeat())
        if not parts:
            return SequenceNode(tuple())
        if len(parts) == 1:
            return parts[0]
        return SequenceNode(tuple(parts))

    def parse_repeat(self) -> Node:
        node = self.parse_atom()
        quantifier = self.peek()
        if quantifier == "?":
            self.take()
            return RepeatNode(node, 0, 1)
        if quantifier == "*":
            self.take()
            return RepeatNode(node, 0, None)
        if quantifier == "+":
            self.take()
            return RepeatNode(node, 1, None)
        if quantifier == "{":
            self.take()
            start = self.index
            while self.peek() is not None and self.peek().isdigit():
                self.take()
            minimum = int(self.pattern[start:self.index] or "0")
            maximum: Optional[int] = minimum
            if self.peek() == ",":
                self.take()
                start = self.index
                while self.peek() is not None and self.peek().isdigit():
                    self.take()
                maximum = int(self.pattern[start:self.index]) if self.index > start else None
            if self.peek() != "}":
                raise PatternError(f"unclosed repetition in {self.pattern!r}")
            self.take()
            if maximum is not None and maximum < minimum:
                raise PatternError(f"invalid repetition {{{minimum},{maximum}}}")
            return RepeatNode(node, minimum, maximum)
        return node

    def parse_atom(self) -> Node:
        ch = self.take()
        if ch == "(":
            if self.peek() == "?":
                self.take()
                if self.take() != ":":
                    raise PatternError("only non-capturing groups are supported")
            node = self.parse_alternation()
            if self.take() != ")":
                raise PatternError("unclosed group")
            return node
        if ch == "[":
            return self.parse_class()
        if ch == "\\":
            escaped = self.take()
            if escaped != "d":
                raise PatternError(f"unsupported escape \\{escaped}")
            return CharSet(frozenset(DIGITS))
        if ch in DIGITS:
            return CharSet(frozenset((ch,)))
        raise PatternError(f"unsupported character {ch!r} in {self.pattern!r}")

    def parse_class(self) -> Node:
        digits: Set[str] = set()
        negated = False
        if self.peek() == "^":
            negated = True
            self.take()
        while True:
            ch = self.take()
            if ch == "]":
                break
            if ch == "\\":
                if self.take() != "d":
                    raise PatternError("only \\d is supported inside character classes")
                digits.update(DIGITS)
                continue
            if ch not in DIGITS:
                raise PatternError(f"unsupported class character {ch!r}")
            if self.peek() == "-" and self.index + 1 < len(self.pattern):
                # A dash is a range only when followed by another digit and a
                # closing bracket; otherwise it is a literal member.
                lookahead = self.pattern[self.index + 1]
                if lookahead in DIGITS:
                    self.take()
                    end = self.take()
                    for value in range(int(ch), int(end) + 1):
                        digits.add(str(value))
                    continue
            digits.add(ch)
        if not digits:
            raise PatternError("empty character class")
        if negated:
            digits = set(DIGITS) - digits
        return CharSet(frozenset(digits))


# ---------------------------------------------------------------------------
# Thompson NFA
# ---------------------------------------------------------------------------


@dataclass
class NfaState:
    epsilon: List[int] = field(default_factory=list)
    transitions: List[Tuple[FrozenSet[str], int]] = field(default_factory=list)


class Nfa:
    def __init__(self) -> None:
        self.states: List[NfaState] = []

    def new_state(self) -> int:
        self.states.append(NfaState())
        return len(self.states) - 1

    def add_epsilon(self, source: int, target: int) -> None:
        self.states[source].epsilon.append(target)

    def add_transition(self, source: int, digits: FrozenSet[str], target: int) -> None:
        self.states[source].transitions.append((digits, target))


def build_fragment(node: Node, nfa: Nfa) -> Tuple[int, int]:
    if isinstance(node, CharSet):
        start = nfa.new_state()
        end = nfa.new_state()
        nfa.add_transition(start, node.digits, end)
        return start, end
    if isinstance(node, SequenceNode):
        if not node.parts:
            start = nfa.new_state()
            end = nfa.new_state()
            nfa.add_epsilon(start, end)
            return start, end
        first_start, first_end = build_fragment(node.parts[0], nfa)
        current_end = first_end
        for part in node.parts[1:]:
            part_start, part_end = build_fragment(part, nfa)
            nfa.add_epsilon(current_end, part_start)
            current_end = part_end
        return first_start, current_end
    if isinstance(node, AlternationNode):
        start = nfa.new_state()
        end = nfa.new_state()
        for option in node.options:
            option_start, option_end = build_fragment(option, nfa)
            nfa.add_epsilon(start, option_start)
            nfa.add_epsilon(option_end, end)
        return start, end
    if isinstance(node, RepeatNode):
        return build_repeat(node, nfa)
    raise TypeError(f"unknown node {node!r}")


def build_repeat(node: RepeatNode, nfa: Nfa) -> Tuple[int, int]:
    start = nfa.new_state()
    end = nfa.new_state()
    current = start
    for _ in range(node.minimum):
        part_start, part_end = build_fragment(node.node, nfa)
        nfa.add_epsilon(current, part_start)
        current = part_end
    if node.maximum is None:
        loop_start, loop_end = build_fragment(node.node, nfa)
        nfa.add_epsilon(current, loop_start)
        nfa.add_epsilon(loop_end, loop_start)
        nfa.add_epsilon(loop_end, end)
        nfa.add_epsilon(current, end)
    else:
        for _ in range(node.maximum - node.minimum):
            nfa.add_epsilon(current, end)
            part_start, part_end = build_fragment(node.node, nfa)
            nfa.add_epsilon(current, part_start)
            current = part_end
        nfa.add_epsilon(current, end)
    return start, end


# ---------------------------------------------------------------------------
# Subset construction and minimisation
# ---------------------------------------------------------------------------


@dataclass
class Dfa:
    start: int
    transitions: List[Dict[str, int]]
    accepting: Set[int]

    @property
    def size(self) -> int:
        return len(self.transitions)


def epsilon_closure(nfa: Nfa, states: Iterable[int]) -> FrozenSet[int]:
    result: Set[int] = set()
    stack = list(states)
    while stack:
        state = stack.pop()
        if state in result:
            continue
        result.add(state)
        stack.extend(nfa.states[state].epsilon)
    return frozenset(result)


def determinize(nfa: Nfa, start: int, accept: int) -> Dfa:
    initial = epsilon_closure(nfa, (start,))
    mapping: Dict[FrozenSet[int], int] = {initial: 0}
    queue = [initial]
    transitions: List[Dict[str, int]] = []
    accepting: Set[int] = set()
    while queue:
        current = queue.pop(0)
        index = mapping[current]
        while len(transitions) <= index:
            transitions.append({})
        if accept in current:
            accepting.add(index)
        for digit in DIGITS:
            targets: Set[int] = set()
            for state in current:
                for digits, target in nfa.states[state].transitions:
                    if digit in digits:
                        targets.add(target)
            if not targets:
                continue
            closure = epsilon_closure(nfa, targets)
            target_index = mapping.get(closure)
            if target_index is None:
                target_index = len(mapping)
                mapping[closure] = target_index
                queue.append(closure)
            transitions[index][digit] = target_index
    return Dfa(0, transitions, accepting)


def remove_dead_states(dfa: Dfa) -> Dfa:
    reverse: List[List[int]] = [[] for _ in range(dfa.size)]
    for source, edges in enumerate(dfa.transitions):
        for target in edges.values():
            reverse[target].append(source)
    live: Set[int] = set(dfa.accepting)
    stack = list(dfa.accepting)
    while stack:
        state = stack.pop()
        for source in reverse[state]:
            if source not in live:
                live.add(source)
                stack.append(source)
    if len(live) == dfa.size:
        return dfa
    remap: Dict[int, int] = {}
    for state in range(dfa.size):
        if state in live:
            remap[state] = len(remap)
    transitions: List[Dict[str, int]] = []
    for state in range(dfa.size):
        if state not in remap:
            transitions.append({})
            continue
        edges = {
            digit: remap[target]
            for digit, target in dfa.transitions[state].items()
            if target in remap
        }
        transitions.append(edges)
    return Dfa(
        start=remap[dfa.start],
        transitions=transitions[: len(remap)],
        accepting={remap[state] for state in dfa.accepting if state in remap},
    )


def minimize(dfa: Dfa) -> Dfa:
    """Partition-refinement minimisation, preserving the start state."""
    dfa = remove_dead_states(dfa)
    if dfa.size <= 1:
        return dfa
    states = list(range(dfa.size))
    # Initial partition: accepting versus non-accepting.
    partition: Dict[int, int] = {
        state: (0 if state in dfa.accepting else 1) for state in states
    }
    while True:
        signatures: Dict[Tuple[int, ...], int] = {}
        new_partition: Dict[int, int] = {}
        for state in states:
            signature = (
                partition[state],
                *(partition[dfa.transitions[state].get(digit, -1)]
                  if digit in dfa.transitions[state] else -1
                  for digit in DIGITS),
            )
            group = signatures.get(signature)
            if group is None:
                group = len(signatures)
                signatures[signature] = group
            new_partition[state] = group
        if new_partition == partition:
            break
        partition = new_partition
    group_count = len(set(partition.values()))
    if group_count == dfa.size:
        return dfa
    representative: Dict[int, int] = {}
    remap: Dict[int, int] = {}
    for group in sorted(set(partition.values())):
        representative[group] = len(representative)
    for state in states:
        remap[state] = representative[partition[state]]
    transitions: List[Dict[str, int]] = [{} for _ in range(group_count)]
    for state in states:
        target_group = remap[state]
        for digit, target in dfa.transitions[state].items():
            transitions[target_group][digit] = remap[target]
    accepting = {remap[state] for state in dfa.accepting}
    return Dfa(remap[dfa.start], transitions, accepting)


def lengthen(dfa: Dfa, minimum: int, maximum: int) -> Dfa:
    """Product with a bounded length counter (states encode their length)."""
    # Remove paths that can never accept before multiplying, which keeps the
    # product small for patterns with impossible branches.
    dfa = remove_dead_states(dfa)
    start = (dfa.start, 0)
    mapping: Dict[Tuple[int, int], int] = {start: 0}
    queue = [start]
    transitions: List[Dict[str, int]] = []
    accepting: Set[int] = set()
    while queue:
        current = queue.pop(0)
        index = mapping[current]
        while len(transitions) <= index:
            transitions.append({})
        state, length = current
        if state in dfa.accepting and minimum <= length <= maximum:
            accepting.add(index)
        if length >= maximum:
            continue
        for digit, target in dfa.transitions[state].items():
            key = (target, length + 1)
            target_index = mapping.get(key)
            if target_index is None:
                target_index = len(mapping)
                mapping[key] = target_index
                queue.append(key)
            transitions[index][digit] = target_index
    return Dfa(0, transitions, accepting)


def compile_pattern(pattern: str, minimum: int, maximum: int) -> Dfa:
    parser = Parser(pattern)
    tree = parser.parse()
    nfa = Nfa()
    start, accept = build_fragment(tree, nfa)
    dfa = determinize(nfa, start, accept)
    dfa = minimize(dfa)
    dfa = lengthen(dfa, minimum, maximum)
    return remove_dead_states(dfa)


# ---------------------------------------------------------------------------
# Analysis helpers
# ---------------------------------------------------------------------------


def count_paths(dfa: Dfa) -> List[int]:
    """Number of accepting suffixes from every state (the graph is a DAG)."""
    memo: Dict[int, int] = {}
    visiting: Set[int] = set()

    def visit(state: int) -> int:
        if state in memo:
            return memo[state]
        if state in visiting:
            raise PatternError("DFA contains a cycle; length product is missing")
        visiting.add(state)
        total = 1 if state in dfa.accepting else 0
        for target in dfa.transitions[state].values():
            total += visit(target)
        visiting.remove(state)
        memo[state] = total
        return total

    for state in range(dfa.size):
        visit(state)
    return [memo.get(state, 0) for state in range(dfa.size)]


def count_exact(dfa: Dfa, state: int, remaining: int) -> int:
    memo: Dict[Tuple[int, int], int] = {}

    def visit(current: int, left: int) -> int:
        key = (current, left)
        if key in memo:
            return memo[key]
        if left == 0:
            value = 1 if current in dfa.accepting else 0
        else:
            value = sum(visit(target, left - 1) for target in dfa.transitions[current].values())
        memo[key] = value
        return value

    return visit(state, remaining)


def count_by_length(dfa: Dfa, length: int) -> int:
    return count_exact(dfa, dfa.start, length)


def state_after(dfa: Dfa, digits: str) -> Optional[int]:
    state = dfa.start
    for digit in digits:
        state = dfa.transitions[state].get(digit, -1)
        if state < 0:
            return None
    return state


def enumerate_prefix(dfa: Dfa, prefix: str, limit: int = 5) -> List[str]:
    state = state_after(dfa, prefix)
    if state is None:
        return []
    out: List[str] = []

    def visit(current: int, text: str) -> None:
        if len(out) >= limit:
            return
        if current in dfa.accepting:
            out.append(text)
        for digit in DIGITS:
            target = dfa.transitions[current].get(digit)
            if target is not None:
                visit(target, text + digit)
                if len(out) >= limit:
                    return

    visit(state, prefix)
    return out


def to_json(dfa: Dfa, iso: str, line_type: str, plan_version: int) -> dict:
    counts = [count_by_length(dfa, length) for length in range(1, 16)]
    return {
        "iso": iso,
        "type": line_type,
        "planVersion": plan_version,
        "start": dfa.start,
        "accept": sorted(dfa.accepting),
        "lengthCounts": counts,
        "total": sum(counts),
        "transitions": [
            [edges.get(digit, -1) for digit in DIGITS]
            for edges in dfa.transitions
        ],
    }
