
# GoToday

전시, 공연, 팝업 등 문화행사를 발견하고 예약할 수 있는 문화 플랫폼

---

## 주요 기능

| 기능 | 설명 |
|------|------|
| 콘텐츠 탐색 | 전시·팝업 콘텐츠 검색, 필터링, 카테고리 조회 |
| 예약 시스템 | 날짜·시간·인원 선택 및 현장 대기/사전 예약 |
| 결제 | Toss Payments 연동 결제 및 환불 처리 |
| 소셜 로그인 | 카카오·네이버 OAuth 로그인 |
| 리뷰 & Q&A | 콘텐츠 리뷰 작성, 문의·업체 답변 |
| 마이페이지 | 예약 조회, 리뷰 관리, 취소·환불 신청 |
| 업체 관리 | 콘텐츠 등록·관리, 예약 현황 조회 |
| 관리자 | 사용자·콘텐츠 관리, 콘텐츠 승인 |

---

## 기술 스택

### Backend
- **Java 11**
- **Spring MVC 5.2.25**
- **MyBatis 3.5.19**
- **HikariCP 7.0.2** (Connection Pool)
- **MariaDB** (AWS RDS ap-northeast-2)

### Frontend
- **JSP** (66개 템플릿)
- **CSS** (30+ 파일)
- **Naver Smart Editor** (Rich Text Editor)

### 외부 연동
- **Kakao OAuth** — 소셜 로그인
- **Naver OAuth** — 소셜 로그인
- **Toss Payments** — 결제 처리

### 빌드 & 배포
- **Maven** (WAR 패키징)
- **Docker + GitHub Actions** 
- **AWS RDS** (MariaDB)

---

## 프로젝트 구조

```
src/main/
├── java/
│   ├── config/                   # Spring MVC 설정
│   ├── kr/co/gotoday/
│   │   ├── user/                 # 사용자 관리
│   │   ├── content/              # 콘텐츠 (전시, 팝업)
│   │   ├── reservation/          # 예약 시스템
│   │   ├── payment/              # 결제
│   │   ├── review/               # 리뷰
│   │   ├── mypage/               # 마이페이지
│   │   ├── vendor/               # 업체 관리
│   │   ├── admin/                # 관리자
│   │   ├── contentLike/          # 좋아요
│   │   ├── cancel/               # 취소·환불
│   │   └── calendar/             # 개인 달력
│   └── util/                     # 인터셉터, 페이징 등 공통 유틸
├── resources/
│   ├── kr/co/gotoday/            # MyBatis XML 매퍼 (14개)
│   ├── application.properties
│   ├── api.properties            # OAuth, 결제 API 키 (gitignore)
│   └── db.properties             # DB 접속 정보 (gitignore)
└── webapp/
    ├── WEB-INF/views/            # JSP 템플릿
    ├── css/                      # 스타일시트
    └── upload/                   # 파일 업로드
```

### 아키텍처

```
Controller → Service → ServiceImpl → Mapper (MyBatis) → MariaDB
                                            ↑
                                      XML 매퍼 쿼리

권한 관리:
  LoginInterceptor   → /reserve/**, /payment/**, /mypage/** 등
  VendorInterceptor  → /vendor/**
  AdminInterceptor   → /admin/**
```