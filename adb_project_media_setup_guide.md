# TeleControl 화면 공유 자동 승인 설정 가이드

TeleControl 앱에서 "도움 요청" 시 매번 나타나는 **"화면을 TeleControl 앱과 공유하시겠습니까?"** 확인 팝업을 영구적으로 건너뛰도록 설정하는 방법입니다.

> [!IMPORTANT]
> 이 설정은 **PC와 USB 케이블을 이용하여 딱 한 번만 실행**하면 됩니다.
> 이후에는 앱을 삭제하지 않는 한 재부팅 후에도 설정이 유지됩니다.

---

## 사전 준비물

- 스마트폰 (TeleControl 앱이 설치된 상태)
- PC (Windows)
- USB 케이블 (스마트폰과 PC 연결용)

---

## 1단계: 스마트폰에서 개발자 옵션 활성화

1. 스마트폰의 **설정** 앱을 엽니다.
2. **휴대전화 정보** (또는 **디바이스 정보**)를 탭합니다.
3. **소프트웨어 정보**를 탭합니다.
4. **빌드 번호**를 **7번 연속** 빠르게 탭합니다.
5. "개발자 모드를 켰습니다" 메시지가 나타나면 성공입니다.

> [!NOTE]
> 이미 개발자 옵션이 활성화되어 있다면 이 단계를 건너뛰세요.

---

## 2단계: USB 디버깅 활성화

1. 스마트폰의 **설정** 앱을 엽니다.
2. **개발자 옵션**을 탭합니다.
   - 삼성 기기: 설정 > 개발자 옵션
   - 일부 기기: 설정 > 시스템 > 개발자 옵션
3. **USB 디버깅** 항목을 찾아 **스위치를 켭니다(ON)**.
4. 경고 팝업이 나타나면 **확인**을 누릅니다.

---

## 3단계: USB 케이블로 PC와 스마트폰 연결

1. USB 케이블로 스마트폰을 PC에 연결합니다.
2. 스마트폰 화면에 **"USB 디버깅을 허용하시겠습니까?"** 팝업이 나타납니다.
3. **"이 컴퓨터에서 항상 허용"에 체크(✓)**합니다.
4. **확인(허용)**을 누릅니다.

> [!WARNING]
> 이 팝업이 보이지 않으면 USB 케이블을 뽑았다가 다시 연결해 보세요.
> 그래도 나타나지 않으면 **개발자 옵션 > USB 디버깅 권한 취소** 후 케이블을 다시 연결하세요.

---

## 4단계: PC에서 ADB 명령 실행

### 4-1. PowerShell 열기

1. PC에서 **Windows 키**를 누릅니다.
2. `PowerShell`을 입력하고 **Windows PowerShell**을 클릭하여 실행합니다.

### 4-2. ADB가 있는 폴더로 이동

아래 명령을 PowerShell에 복사하여 붙여넣고 **Enter**를 누릅니다:

```powershell
cd "G:\Program\web_program\Android_Program\youtube_downloader_v1.0_android\android-sdk\platform-tools"
```

### 4-3. 스마트폰 연결 상태 확인

아래 명령을 실행하여 스마트폰이 정상적으로 인식되는지 확인합니다:

```powershell
.\adb.exe devices
```

**정상 출력 예시:**
```
List of devices attached
XXXXXXXXXXXXXXX    device
```

> [!CAUTION]
> `unauthorized`로 표시되면 스마트폰 화면에서 USB 디버깅 허용 팝업을 승인해 주세요.
> `offline`이나 아무것도 표시되지 않으면 USB 케이블을 뽑았다가 다시 연결하세요.

### 4-4. 화면 공유 자동 승인 명령 실행 ⭐

아래 명령을 복사하여 붙여넣고 **Enter**를 누릅니다:

```powershell
.\adb.exe shell appops set com.sbs.telecom.remote PROJECT_MEDIA allow
```

**에러 메시지 없이 다음 줄로 넘어가면 성공**입니다.

---

## 5단계: 설정 확인 (선택사항)

설정이 제대로 적용되었는지 확인하려면 아래 명령을 실행합니다:

```powershell
.\adb.exe shell appops get com.sbs.telecom.remote PROJECT_MEDIA
```

**정상 출력 예시:**
```
PROJECT_MEDIA: allow
```

---

## 6단계: 동작 테스트

1. USB 케이블을 분리합니다.
2. 스마트폰에서 **TeleControl** 앱을 엽니다.
3. **도움 받기** > **도움 요청** 버튼을 누릅니다.
4. 기존에 나타나던 **"화면을 TeleControl 앱과 공유하시겠습니까?"** 팝업이 **더 이상 뜨지 않고** 바로 화면 공유가 시작되면 설정 완료입니다. 🎉

---

## 참고 사항

### 설정이 초기화되는 경우
- **앱을 삭제 후 재설치**한 경우 → 위 ADB 명령을 한 번 더 실행해야 합니다.
- **공장 초기화**를 한 경우 → 모든 설정을 처음부터 다시 해야 합니다.
- **재부팅**만 한 경우 → 설정이 유지되므로 다시 실행할 필요 없습니다.

### 설정을 해제(원래대로 복구)하고 싶은 경우

```powershell
.\adb.exe shell appops set com.sbs.telecom.remote PROJECT_MEDIA ignore
```

이 명령을 실행하면 다시 매번 화면 공유 확인 팝업이 표시됩니다.

---

## 전체 명령 요약 (복사용)

```powershell
# 1. ADB 폴더로 이동
cd "G:\Program\web_program\Android_Program\youtube_downloader_v1.0_android\android-sdk\platform-tools"

# 2. 스마트폰 연결 확인
.\adb.exe devices

# 3. 화면 공유 자동 승인 설정
.\adb.exe shell appops set com.sbs.telecom.remote PROJECT_MEDIA allow

# 4. 설정 확인
.\adb.exe shell appops get com.sbs.telecom.remote PROJECT_MEDIA
```
