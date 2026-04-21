# Open Questions

1. S32DS 3.5.x가 배포하는 실제 JRE와 `com.sun.net.httpserver` export 상태를 각 설치본에서 확인할 필요가 있다.
2. `CommandContributionItem`에서 command id를 안정적으로 추출하는 방법은 Eclipse 4.20 구현 상세에 일부 의존할 수 있다. 현재 구현은 공개 API 중심으로 최대한 보수적으로 값을 채운다.
3. Tycho target을 Eclipse 2021-06 release repository만으로 두었는데, 사내망 또는 오프라인 환경에서 mirror 설정이 필요한지 사용자 환경 검증이 필요하다.
4. Windows ACL 축소가 일부 파일시스템에서 실패할 수 있다. 현재는 best-effort로 처리한다.
5. `ProcessHandle.current().pid()`는 Java 11 전제다. S32DS 포함 JRE가 다를 경우 대체 경로 점검이 필요하다.
6. `POST /visible-menu` 결과는 현재 perspective, selection, active editor 상태에 강하게 의존한다. 빈 결과가 실패인지 정상인지 더 구체적인 운영 기준이 필요하다.
