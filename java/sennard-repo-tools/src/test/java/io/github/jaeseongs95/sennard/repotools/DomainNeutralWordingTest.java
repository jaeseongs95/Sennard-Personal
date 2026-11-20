// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.repotools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** The wording rule against the sentences that define it and against the repository itself. */
class DomainNeutralWordingTest {
    private static List<DomainNeutralWording.Finding> scan(String text) {
        return DomainNeutralWording.findFieldWording(text, null);
    }

    private static String terms(List<DomainNeutralWording.Finding> findings) {
        return findings.stream().map(DomainNeutralWording.Finding::term).distinct().sorted().collect(Collectors.joining(","));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "결과물(코드, 문서, 영상)의 근거를 확인한다.",
            "디자인·편집·촬영·구현 같은 제작은 맡은 에이전트가 한다.",
            "목적이 이미 이뤄졌는지 실제 상태(예: PR이면 base와 head의 포함 관계)로 확인한다.",
            "화면이면 아래 기준을 함께 적용한다.",
            "## 화면일 때\n\n- 기존 컴포넌트를 재사용하고 렌더링을 정리한다.",
            "구현, 반복 실패 원인 분석, 릴리스 승인에는 사용하지 않는다.",
            "코드 작성 단계에는 `solid-design`을 먼저 호출한다.",
            "문서를 검토하며, 코드 작성 단계에는 `solid-design`을 먼저 호출한다.",
            "Not for implementation, repeated-failure diagnosis or release approval.",
            "Not automatically for general questions, non-visual CLI work or logs merely mentioning UI.",
            "Do not use it for code review.",
            "예를 들어 코드 변경을 검토한다.",
            "결과물(`코드`, 문서, 영상)의 근거를 확인한다.",
            "원문 `skills/code-review/SKILL.md`와 `run_skill_check`를 읽는다.",
            "명령의 종료 코드 0만으로 동작을 인정하지 않는다.",
            "소프트웨어 테스트와 표준 runner가 있는 경우에는 그 runner로 확인한다.",
            "```yaml\nimplementers: []\n```\n```markdown\n- Implementer(s):\n```",
            "직접 확인한 diff와 의미 동일성 근거를 기록한다.",
            "코드 작성 단계에는 `test-engineering`을 호출한다.",
    })
    void aFieldNamedAsOneExampleOrBranchOrExclusionPasses(String sentence) {
        assertEquals(List.of(), scan(sentence), sentence);
    }

    static Stream<Arguments> tiedToOneField() {
        return Stream.of(
                Arguments.of("코드 변경의 근거를 수용 기준별로 대조한다.", "코드"),
                Arguments.of("구현자와 분리된 감사자가 최종 변경을 확인한다.", "구현"),
                Arguments.of("diff 크기보다 실패 영향을 기준으로 판단한다.", "diff"),
                Arguments.of("작업 브랜치 commit/push와 CI 실행은 별도 승인을 요구하지 않는다.", "CI"),
                Arguments.of("허용된 개발 작업에서 모델에 전달할 지시를 바꿀 때 사용한다.", "개발"),
                Arguments.of("Use when reviewing code changes.", "code"),
                Arguments.of("코드 작업에만 쓰고 그 밖의 작업에는 쓰지 않는다.", "코드"),
                Arguments.of("구현자와 분리된 검토자로서 판정하고, 승인 결정을 대신하지 않는다.", "구현"),
                Arguments.of("Not for documents; use it for code changes.", "code"),
                Arguments.of("Use only for code changes and not for documents.", "code"),
                Arguments.of("Review code changes. Not for documents.", "code"),
                Arguments.of("코드 변경에만 쓰며 필요하면 `code-review`를 호출한다.", "코드"),
                Arguments.of("코드 변경의 근거를 대조하며, 필요하면 `code-review`를 호출한다.", "코드"),
                Arguments.of("예를 들어 영상을 검토하며, 코드 변경에만 적용한다.", "코드"),
                Arguments.of("예를 들어 영상을 검토하며, 코드 변경의 근거를 대조한다.", "코드"),
                Arguments.of("Use only for `code` changes.", "code"),
                Arguments.of("`코드` 변경에만 사용한다.", "코드"),
                Arguments.of("소프트웨어 테스트와 표준 runner가 있는 경우에는 확인하며, 구현자와 분리된 감사자가 판정한다.", "구현"),
                Arguments.of("저장소 관례가 필요하고 이미 제공됐다면 확인하며, 대상 저장소의 방법을 강제한다.", "저장소"),
                Arguments.of("변경 작업자는 명세를 사례로 추가하고 표준 runner로 실행한다.", "runner"),
                Arguments.of("직접 확인한 diff와 의미 동일성 근거를 기록하며, 구현자와 분리된 감사자가 판정한다.", "구현"));
    }

    @ParameterizedTest
    @MethodSource("tiedToOneField")
    void aSentenceTiedToOneFieldIsCaught(String sentence, String term) {
        assertTrue(scan(sentence).stream().anyMatch(finding -> finding.term().equals(term)), term + ": " + sentence);
    }

    @Test
    void aScreenOnlyDescriptionIsCaughtOnItsApplicabilityLines() {
        String markdown = String.join("\n", "---", "name: x", "description: >", "  화면 사용자 흐름과 개발 범위를 설계한다.", "  웹 데스크톱 모바일 화면 작업에 쓴다.", "---", "본문");
        List<DomainNeutralWording.Finding> findings = DomainNeutralWording.findFieldWording(markdown, DomainNeutralWording.skillLines(markdown));
        assertEquals("개발,데스크톱,모바일,웹,화면", terms(findings));
        assertEquals(List.of(4, 5), findings.stream().map(DomainNeutralWording.Finding::line).distinct().sorted().toList());
    }

    @Test
    void theRepositoryMeetsTheRule() {
        DomainNeutralWording.Report report = DomainNeutralWording.checkRepository(new Repo(Path.of(System.getProperty("sennard.repo.root"))));
        assertEquals(List.of(), report.problems());
        assertEquals(List.of(), report.findings());
    }
}
