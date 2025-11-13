# Getting Started - Immediate Next Steps

This guide helps you start implementing the Gmail TUI application based on the comprehensive analysis.

---

## 📋 What You Have Now

**Documentation Created:**
1. **ROADMAP.md** - Complete development roadmap (6-8 weeks, phased approach)
2. **CRITICAL_ISSUES.md** - Bugs that must be fixed immediately
3. **This file** - Quick start guide

**Current Codebase Status:**
- ✅ Basic Gmail API integration working
- ✅ OAuth authentication functional
- ✅ Can fetch messages (albeit slowly)
- ❌ 4 critical bugs preventing proper functionality
- ❌ No user interface (TUI not implemented)
- ❌ No caching or performance optimization
- ❌ Using outdated Java 11 and old dependencies

---

## 🚀 Quick Start - Week 1

### Day 1: Fix Critical Bugs

**Goal:** Fix the 4 critical bugs that break core functionality

**Tasks:**

#### 1. Fix Header Filtering Bug

**File:** `src/main/java/api/gmail/APIConnection.java`
**Line:** 166

**Replace:**
```java
if (header.getName().equals("Delivered-To|Date|From|To|Subject"))
    selectedHeaders.put(header.getName(), header.getValue());
```

**With:**
```java
Set<String> wantedHeaders = Set.of("Delivered-To", "Date", "From", "To", "Subject");
if (wantedHeaders.contains(header.getName())) {
    selectedHeaders.put(header.getName(), header.getValue());
}
```

#### 2. Fix Data Overwrite Bug

**File:** `src/main/java/api/gmail/APIConnection.java`
**Lines:** 213-214

**Replace:**
```java
data.put(parts.indexOf(part), decode(part.getBody().getData()));
data.put(parts.indexOf(part), part.getMimeType());
```

**With:**
```java
String content = decode(part.getBody().getData());
String mimeType = part.getMimeType();
data.put(parts.indexOf(part), mimeType + ": " + content);
```

#### 3. Fix Resource Leak

**File:** `src/main/java/api/gmail/APIConnection.java`
**Lines:** 126-137

**Replace:**
```java
private static Credential getCredentials() throws IOException {
    logger.log(Level.INFO, ">> loading credentials");
    InputStream in = APIConnection.class.getResourceAsStream("/credentials.json");
    if (in == null) throw new FileNotFoundException("Resource not found: " + "/credentials.json");
    GoogleClientSecrets clientSecrets = GoogleClientSecrets.load(JSON_FACTORY, new InputStreamReader(in));
    // ... rest
}
```

**With:**
```java
private static Credential getCredentials() throws IOException {
    logger.log(Level.INFO, ">> loading credentials");
    try (InputStream in = APIConnection.class.getResourceAsStream("/credentials.json")) {
        if (in == null) {
            throw new FileNotFoundException("Resource not found: /credentials.json");
        }
        GoogleClientSecrets clientSecrets = GoogleClientSecrets.load(
            JSON_FACTORY,
            new InputStreamReader(in)
        );
        // ... rest (keep existing code after this point)
    }
}
```

#### 4. Fix Null Safety

**File:** `src/main/java/api/gmail/APIConnection.java`
**Lines:** 96-101

**Replace:**
```java
private static List<MessagePart> getPayloadParts(List<MessagePart> handle) {
    if (handle == null) return Collections.emptyList();
    if (handle.get(0).getBody().getData() != null) return handle;
    if (handle.get(0).getParts() != null) return getPayloadParts(handle.get(0).getParts());
    return Collections.emptyList();
}
```

**With:**
```java
private static List<MessagePart> getPayloadParts(List<MessagePart> handle) {
    if (handle == null || handle.isEmpty()) {
        return Collections.emptyList();
    }

    MessagePart firstPart = handle.get(0);
    if (firstPart == null) {
        return Collections.emptyList();
    }

    if (firstPart.getBody() != null && firstPart.getBody().getData() != null) {
        return handle;
    }

    if (firstPart.getParts() != null) {
        return getPayloadParts(firstPart.getParts());
    }

    return Collections.emptyList();
}
```

**Test:**
```bash
./gradlew clean build
./gradlew test
```

---

### Day 2: Upgrade to Java 21

**Goal:** Modernize to Java 21 (latest LTS)

#### 1. Update build.gradle

**File:** `build.gradle`

**Replace lines 13-14:**
```gradle
sourceCompatibility = 11
targetCompatibility = 11
```

**With:**
```gradle
sourceCompatibility = 21
targetCompatibility = 21
```

#### 2. Update Dependencies

**File:** `build.gradle`

**Replace entire dependencies block (lines 45-53):**
```gradle
dependencies {
    implementation 'com.google.api-client:google-api-client:1.35.1'
    implementation 'com.google.oauth-client:google-oauth-client-jetty:1.34.1'
    implementation 'com.google.apis:google-api-services-gmail:v1-rev20220404-1.32.1'
    implementation 'email-attachments:email-attachments:1.2.4'
    implementation 'javax.mail:mail:1.4.7'
    implementation 'org.junit.jupiter:junit-jupiter-api:5.8.2'
    implementation 'org.junit.jupiter:junit-jupiter-engine:5.8.2'
}
```

**With:**
```gradle
dependencies {
    // Gmail API
    implementation 'com.google.api-client:google-api-client:2.7.0'
    implementation 'com.google.oauth-client:google-oauth-client-jetty:1.36.0'
    implementation 'com.google.apis:google-api-services-gmail:v1-rev20240914-2.0.0'

    // Logging (modern replacement for java.util.logging)
    implementation 'org.slf4j:slf4j-api:2.0.16'
    implementation 'ch.qos.logback:logback-classic:1.5.12'

    // Caching
    implementation 'com.github.ben-manes.caffeine:caffeine:3.1.8'

    // TUI Framework
    implementation 'com.googlecode.lanterna:lanterna:3.1.2'

    // Utilities
    implementation 'com.google.guava:guava:33.3.1-jre'

    // Testing
    testImplementation 'org.junit.jupiter:junit-jupiter:5.11.3'
    testImplementation 'org.mockito:mockito-core:5.14.2'
    testImplementation 'org.mockito:mockito-junit-jupiter:5.14.2'
    testImplementation 'org.assertj:assertj-core:3.27.0'
}
```

#### 3. Remove Unused Import

**File:** `src/main/java/api/gmail/mail/body/Body.java`
**Line:** 4

**Delete:**
```java
import java.util.concurrent.Callable;
```

**Test:**
```bash
./gradlew clean build
./gradlew test
```

---

### Day 3: Convert to Records

**Goal:** Use Java 17+ records for immutable data classes

#### 1. Convert Mail.java

**File:** `src/main/java/api/gmail/mail/factory/Mail.java`

**Replace entire file:**
```java
package api.gmail.mail.factory;

import api.gmail.mail.body.Body;
import api.gmail.mail.headers.Headers;

import java.util.Objects;

/**
 * Data structure holding the necessary information about the email.
 */
public record Mail(String id, Headers headers, Body body) {
    /**
     * Compact constructor with validation.
     */
    public Mail {
        Objects.requireNonNull(id, "id cannot be null");
        Objects.requireNonNull(headers, "headers cannot be null");
        Objects.requireNonNull(body, "body cannot be null");
    }
}
```

#### 2. Convert Headers.java

**File:** `src/main/java/api/gmail/mail/headers/Headers.java`

**Replace entire file:**
```java
package api.gmail.mail.headers;

import java.util.Map;
import java.util.Objects;

/**
 * Data structure containing the headers list.
 */
public record Headers(Map<String, String> headers) {
    /**
     * Compact constructor with validation.
     */
    public Headers {
        Objects.requireNonNull(headers, "headers map cannot be null");
        headers = Map.copyOf(headers); // Make immutable copy
    }
}
```

#### 3. Convert Body.java

**File:** `src/main/java/api/gmail/mail/body/Body.java`

**Replace entire file:**
```java
package api.gmail.mail.body;

import java.util.Map;
import java.util.Objects;

/**
 * Data structure holding email body content.
 */
public record Body(String snippet, Map<Integer, String> parts, String type) {
    /**
     * Compact constructor with validation.
     */
    public Body {
        Objects.requireNonNull(snippet, "snippet cannot be null");
        Objects.requireNonNull(parts, "parts map cannot be null");
        Objects.requireNonNull(type, "type cannot be null");
        parts = Map.copyOf(parts); // Make immutable copy
    }
}
```

#### 4. Update Usages

**File:** `src/main/java/api/gmail/mail/factory/MailFactory.java`

If your code was accessing fields directly (e.g., `mail.id`), update to use accessor methods (e.g., `mail.id()`).

**Example:**
```java
// Before (if you had this pattern):
String id = mail.id;

// After (with records):
String id = mail.id();
```

**Test:**
```bash
./gradlew clean build
./gradlew test
```

---

### Day 4-5: Test & Verify

**Goal:** Ensure everything works after refactoring

#### Test Checklist

```bash
# 1. Clean build
./gradlew clean

# 2. Compile
./gradlew compileJava

# 3. Run tests
./gradlew test

# 4. Run application (manual test)
./gradlew run

# 5. Verify OAuth flow works
# 6. Verify can fetch messages
# 7. Verify headers are extracted (check logs)
# 8. Verify email bodies are retrieved
```

#### Create Manual Test

Create a simple test to verify fixes:

**File:** `src/test/java/api/gmail/ManualVerificationTest.java`

```java
package api.gmail;

import api.gmail.mail.Inbox;
import api.gmail.mail.factory.Mail;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Manual verification that critical bugs are fixed.
 * This will actually connect to Gmail API, so run manually.
 */
public class ManualVerificationTest {

    @Test
    public void verifyHeadersExtracted() {
        Inbox inbox = new Inbox();
        List<Mail> emails = inbox.getInboxList();

        if (!emails.isEmpty()) {
            Mail firstEmail = emails.get(0);

            // Verify headers are not empty (bug fix #1)
            System.out.println("Headers: " + firstEmail.headers().headers());
            assert !firstEmail.headers().headers().isEmpty() : "Headers should not be empty!";

            // Verify body parts contain content, not just MIME types (bug fix #2)
            System.out.println("Body parts: " + firstEmail.body().parts());
            firstEmail.body().parts().forEach((index, content) -> {
                assert content.contains(":") || content.length() > 20 :
                    "Body parts should contain content, not just MIME types";
            });

            System.out.println("✅ All critical bugs appear to be fixed!");
        }
    }
}
```

**Run:**
```bash
./gradlew test --tests ManualVerificationTest
```

---

## 🎯 Decision Points

Before proceeding to Week 2+, decide:

### 1. Dependency Injection Framework

**Option A: Google Guice (Recommended for this project)**
- Lightweight (~1MB)
- Easy to learn
- Perfect for standalone applications
- Add: `implementation 'com.google.inject:guice:7.0.0'`

**Option B: Spring Boot**
- Full-featured framework
- Better GCP integration
- Heavier (~30MB)
- Add: `implementation 'org.springframework.boot:spring-boot-starter:3.4.0'`

**Decision:** _____________________

### 2. GCP Integration

**Do you want to use GCP services?**
- [ ] YES - Use Secret Manager for credentials, Cloud Logging, optional Firestore
- [ ] NO - Keep it simple, local-only application

**Decision:** _____________________

### 3. Scope

**Which features are must-have for v1.0?**
- [ ] Browse inbox (read-only) - **CORE**
- [ ] Search emails - **CORE**
- [ ] View email details - **CORE**
- [ ] Mark as read/unread
- [ ] Star emails
- [ ] Download attachments
- [ ] Reply to emails
- [ ] Compose new emails
- [ ] Multi-account support

**Decision:** _____________________

---

## 📚 Next Steps After Week 1

Once Week 1 is complete (bugs fixed, Java 21, records), choose your path:

### Path A: TUI First (Fastest to working app)
1. Skip architecture refactoring for now
2. Build basic TUI directly on existing code
3. Get something working quickly
4. Refactor architecture later

**Timeline:** 2-3 weeks to working TUI
**Pros:** Fast results, immediate usability
**Cons:** Technical debt, harder to refactor later

### Path B: Architecture First (Better long-term)
1. Refactor to layered architecture (see ROADMAP.md Phase 1)
2. Add dependency injection
3. Implement caching and virtual threads
4. Then build TUI on solid foundation

**Timeline:** 4-5 weeks to working TUI
**Pros:** Clean, maintainable, testable
**Cons:** Takes longer to see results

**Recommendation:** **Path B** - The architecture refactoring is not that complex and will save you significant pain later. The roadmap makes it manageable by breaking it into clear phases.

---

## 🔗 Resources

**Documentation:**
- Full roadmap: `ROADMAP.md`
- Critical issues: `CRITICAL_ISSUES.md`
- Gmail API docs: https://developers.google.com/gmail/api/guides

**Java 21 Features:**
- Records: https://openjdk.org/jeps/395
- Virtual Threads: https://openjdk.org/jeps/444
- Pattern Matching: https://openjdk.org/jeps/441

**Libraries:**
- Lanterna TUI: https://github.com/mabe02/lanterna
- Google Guice: https://github.com/google/guice
- Caffeine Cache: https://github.com/ben-manes/caffeine

**GCP (if needed):**
- Secret Manager: https://cloud.google.com/secret-manager/docs
- Cloud Logging: https://cloud.google.com/logging/docs

---

## ✅ Week 1 Success Criteria

Before moving to Week 2, ensure:

- [ ] All 4 critical bugs fixed
- [ ] Java 21 configured
- [ ] All dependencies updated
- [ ] All data classes converted to records
- [ ] All tests passing
- [ ] Application runs and can fetch emails
- [ ] Headers are properly extracted (verify in logs/tests)
- [ ] Email bodies contain content (not just MIME types)
- [ ] No resource leak warnings
- [ ] Code compiles with zero warnings

**Commands to verify:**
```bash
./gradlew clean build
./gradlew test
./gradlew run
```

All should complete successfully with no errors or warnings.

---

## 💡 Tips

1. **Make small commits:** After each fix, commit with descriptive message
2. **Test frequently:** Run `./gradlew test` after each change
3. **Use branches:** Create feature branches for each phase
4. **Read the full ROADMAP.md:** It has much more detail on architecture and TUI design
5. **Ask questions:** If stuck, review the CRITICAL_ISSUES.md for detailed explanations

---

## 🎉 You're Ready!

You have:
- ✅ Complete analysis of the codebase
- ✅ Comprehensive roadmap (ROADMAP.md)
- ✅ Critical issues identified (CRITICAL_ISSUES.md)
- ✅ Week 1 action plan (this file)

**Start with Day 1 and work through the week. Good luck!**

Once Week 1 is complete, come back to ROADMAP.md for Week 2+ guidance.
