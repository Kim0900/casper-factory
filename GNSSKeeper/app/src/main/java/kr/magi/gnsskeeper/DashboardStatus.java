package kr.magi.gnsskeeper;

/** Display policy only: never changes location requests, power policy or another app. */
final class DashboardStatus {
    enum Band {
        BLUE(0xff64b5f6, "파랑"), GREEN(0xff81c784, "녹색"), YELLOW(0xffffd54f, "노랑"),
        RED(0xffef9a9a, "빨강"), UNKNOWN(0xffb0bec5, "회색");
        final int color; final String label;
        Band(int color, String label) { this.color = color; this.label = label; }
    }
    final Band band; final String label;
    DashboardStatus(Band band, String label) { this.band = band; this.label = label; }
    static DashboardStatus thermal(int status) {
        switch (status) {
            case 0: return new DashboardStatus(Band.BLUE, "열 제한 없음");
            case 1: return new DashboardStatus(Band.GREEN, "경미한 열 제한");
            case 2: return new DashboardStatus(Band.YELLOW, "주의 · 중간 열 제한");
            case 3: return new DashboardStatus(Band.RED, "심한 열 제한 · 냉각 권장");
            case 4: return new DashboardStatus(Band.RED, "위험 · 전력 제한 극대화");
            case 5: return new DashboardStatus(Band.RED, "긴급 · 일부 기능 종료");
            case 6: return new DashboardStatus(Band.RED, "긴급 · 시스템 종료 필요");
            default: return new DashboardStatus(Band.UNKNOWN, "열 상태 확인 불가");
        }
    }
    static DashboardStatus location(boolean running, boolean passive, long fix, long now) {
        if (!running) return new DashboardStatus(Band.UNKNOWN, "관찰 중지");
        if (fix == 0) return new DashboardStatus(Band.BLUE, passive ? "관찰 대기" : "첫 위치 대기");
        if (fix < 0 || now < fix) return new DashboardStatus(Band.UNKNOWN, "측정 시각 확인 불가");
        long age = now - fix;
        if (age <= 5_000_000_000L) return new DashboardStatus(Band.GREEN, "최근 위치 · 5초 이내");
        if (passive) return new DashboardStatus(Band.YELLOW, "갱신 대기 · 5초 초과");
        return age <= 15_000_000_000L
                ? new DashboardStatus(Band.YELLOW, "위치 지연 · 5초 초과")
                : new DashboardStatus(Band.RED, "오래된 위치 · 15초 초과");
    }
    static DashboardStatus service(String phase) {
        if ("RUNNING".equals(phase)) return new DashboardStatus(Band.GREEN, "서비스 실행 중");
        if ("STARTING".equals(phase)) return new DashboardStatus(Band.BLUE, "시작 준비 중");
        if ("STOPPING".equals(phase)) return new DashboardStatus(Band.BLUE, "기록 종료 중");
        if ("ERROR".equals(phase)) return new DashboardStatus(Band.RED, "오류로 중지");
        return new DashboardStatus(Band.UNKNOWN, "중지");
    }
    static DashboardStatus network(boolean known, boolean connected, boolean internet, boolean validated) {
        if (!known) return new DashboardStatus(Band.UNKNOWN, "연결 확인 불가");
        if (!connected || !internet) return new DashboardStatus(Band.RED, "인터넷 연결 없음");
        return validated ? new DashboardStatus(Band.GREEN, "OS 인터넷 확인됨")
                : new DashboardStatus(Band.YELLOW, "인터넷 미확인 / 제한");
    }
    static DashboardStatus power(Boolean saving) {
        return saving == null ? new DashboardStatus(Band.UNKNOWN, "절전 상태 확인 불가")
                : saving ? new DashboardStatus(Band.YELLOW, "절전 모드 켜짐")
                : new DashboardStatus(Band.GREEN, "절전 모드 꺼짐");
    }
    static DashboardStatus permission(boolean fine, boolean coarse, Boolean enabled) {
        if (Boolean.FALSE.equals(enabled)) return new DashboardStatus(Band.RED, "기기 위치 꺼짐");
        if (!fine && !coarse) return new DashboardStatus(Band.RED, "위치 권한 없음");
        if (!fine) return new DashboardStatus(Band.YELLOW, "대략적 위치만 허용");
        return enabled == null ? new DashboardStatus(Band.UNKNOWN, "기기 위치 확인 불가")
                : new DashboardStatus(Band.GREEN, "정확한 위치 허용");
    }
}
