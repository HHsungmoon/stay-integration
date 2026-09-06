#!/usr/bin/env bash
# git commit 전에 저장소 정책 위반을 검사한다.
#
# 저장소 정책 위반(외부 자료 커밋, 금지 표현 노출)은 히스토리에 남으면
# 되돌릴 수 없고, 히스토리 자체가 산출물이라 rewrite로 지우기도 곤란하다.
# 사람이 매번 눈으로 확인하는 대신 여기서 막는다.
set -uo pipefail

input=$(cat)
command=$(printf '%s' "$input" | jq -r '.tool_input.command // ""')

case "$command" in
	*"git commit"*) ;;
	*) exit 0 ;;
esac

root=$(git rev-parse --show-toplevel 2>/dev/null) || exit 0
cd "$root" 2>/dev/null || exit 0

violations=""
notes=""
add_violation() { violations="${violations}  - ${1}"$'\n'; }
add_note() { notes="${notes}  - ${1}"$'\n'; }

# (1) .gitignore가 막고 있는 파일이 강제로 스테이징되었는지
#
# 파일명을 직접 매칭하지 않는 이유: git은 한글 파일명을 이스케이프해 출력하고
# (core.quotepath), macOS는 NFD/NFC 두 표현이 섞인다. .gitignore를 유일한 기준으로 삼으면
# 그 문제를 피하면서 외부 자료·작성 중 문서를 한 번에 커버한다.
while IFS= read -r -d '' f; do
	[ -z "$f" ] && continue
	if git check-ignore -q --no-index -- "$f" 2>/dev/null; then
		add_violation "무시 대상 파일이 강제로 스테이징됨: ${f}"
	fi
done < <(git diff --cached --name-only -z 2>/dev/null)

# (2) 금지 표현
#
# 목록을 이 스크립트에 직접 적으면, 스크립트가 공개될 때 바로 그 표현이 노출된다.
# 그래서 목록은 추적되지 않는 로컬 파일에 두고 읽기만 한다.
terms_file="${root}/.claude/forbidden-terms.local.txt"
if [ -f "$terms_file" ]; then
	haystack=$(printf '%s\n' "$command"; git diff --cached 2>/dev/null)
	while IFS= read -r term; do
		term="${term%$'\r'}"
		[ -z "$term" ] && continue
		case "$term" in \#*) continue ;; esac
		if printf '%s' "$haystack" | grep -qiF -- "$term" 2>/dev/null; then
			add_violation "금지 표현이 포함됨: \"${term}\""
		fi
	done < "$terms_file"
else
	add_note "금지 표현 목록이 없어 해당 검사를 건너뜀 (${terms_file})"
fi

# (3) AI 트레일러
if printf '%s' "$command" | grep -qE 'Co-Authored-By|Claude-Session' 2>/dev/null; then
	add_violation "커밋 메시지에 AI 트레일러가 있음 (Co-Authored-By / Claude-Session)"
fi

if [ -n "$violations" ]; then
	reason="저장소 정책 위반이라 커밋을 차단했습니다."$'\n'"${violations}"
	[ -n "$notes" ] && reason="${reason}참고:"$'\n'"${notes}"
	jq -n --arg r "$reason" '{
		hookSpecificOutput: {
			hookEventName: "PreToolUse",
			permissionDecision: "deny",
			permissionDecisionReason: $r
		}
	}'
fi

exit 0
