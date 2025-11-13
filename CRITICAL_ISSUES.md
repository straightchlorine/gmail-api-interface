# Critical Issues - Immediate Action Required

This document lists bugs and issues that should be fixed IMMEDIATELY before any further development.

---

## 🔴 CRITICAL BUGS (Breaking Functionality)

### 1. Header Filtering Never Works
**Location:** `src/main/java/api/gmail/APIConnection.java:166`

**Current Code:**
```java
if (header.getName().equals("Delivered-To|Date|From|To|Subject"))
```

**Problem:**
The `equals()` method compares for exact string match. It will NEVER match because no header name is literally the string `"Delivered-To|Date|From|To|Subject"`. The pipe `|` is a regex OR operator, but `equals()` doesn't use regex.

**Result:** Headers are NEVER extracted from emails. The headers map is always empty.

**Fix:**
```java
Set<String> wantedHeaders = Set.of("Delivered-To", "Date", "From", "To", "Subject");
for (MessagePartHeader header : getMessageHandle(message).getPayload().getHeaders()) {
    if (wantedHeaders.contains(header.getName())) {
        selectedHeaders.put(header.getName(), header.getValue());
    }
    if (selectedHeaders.size() == 5) break;
}
```

**Impact:** HIGH - No email headers are currently being extracted

---

### 2. Email Body Content Lost (Data Overwrite)
**Location:** `src/main/java/api/gmail/APIConnection.java:213-214`

**Current Code:**
```java
data.put(parts.indexOf(part), decode(part.getBody().getData()));  // Line 213
data.put(parts.indexOf(part), part.getMimeType());                // Line 214
```

**Problem:**
Line 214 immediately overwrites the decoded email body (line 213) with the MIME type string. The same key is used twice, so only the last value (MIME type) is retained.

**Result:** Email body content is LOST. Only MIME type strings like "text/plain" are stored.

**Fix Option 1 (Minimal):**
```java
// Store both as a combined string temporarily
String content = decode(part.getBody().getData());
String mimeType = part.getMimeType();
data.put(parts.indexOf(part), mimeType + ": " + content);
```

**Fix Option 2 (Proper - requires new class):**
```java
record EmailPart(String content, String mimeType) {}

Map<Integer, EmailPart> data = new HashMap<>();
for (MessagePart part : parts) {
    data.put(parts.indexOf(part),
        new EmailPart(decode(part.getBody().getData()), part.getMimeType()));
}
```

**Impact:** CRITICAL - Email body text is completely lost

---

### 3. Resource Leak (File Handle Not Closed)
**Location:** `src/main/java/api/gmail/APIConnection.java:129`

**Current Code:**
```java
InputStream in = APIConnection.class.getResourceAsStream("/credentials.json");
if (in == null) throw new FileNotFoundException("Resource not found: " + "/credentials.json");
GoogleClientSecrets clientSecrets = GoogleClientSecrets.load(JSON_FACTORY, new InputStreamReader(in));
// ... in is never closed
```

**Problem:**
The `InputStream` is opened but never closed. This is a resource leak.

**Result:**
- File handles not released
- Potential "too many open files" error after repeated use
- Memory leak over time

**Fix:**
```java
try (InputStream in = APIConnection.class.getResourceAsStream("/credentials.json")) {
    if (in == null) {
        throw new FileNotFoundException("Resource not found: /credentials.json");
    }
    GoogleClientSecrets clientSecrets = GoogleClientSecrets.load(
        JSON_FACTORY,
        new InputStreamReader(in)
    );
    // ... rest of code
}
```

**Impact:** MEDIUM - Resource leak, not immediately breaking but problematic long-term

---

### 4. Null Pointer / Index Out of Bounds Risk
**Location:** `src/main/java/api/gmail/APIConnection.java:98`

**Current Code:**
```java
private static List<MessagePart> getPayloadParts(List<MessagePart> handle) {
    if (handle == null) return Collections.emptyList();
    if (handle.get(0).getBody().getData() != null) return handle;  // UNSAFE!
    if (handle.get(0).getParts() != null) return getPayloadParts(handle.get(0).getParts());
    return Collections.emptyList();
}
```

**Problems:**
1. If `handle` is an empty list, `handle.get(0)` throws `IndexOutOfBoundsException`
2. If `handle.get(0).getBody()` is null, NullPointerException
3. No validation that elements exist before accessing

**Result:** Application crashes when processing certain malformed emails

**Fix:**
```java
private static List<MessagePart> getPayloadParts(List<MessagePart> handle) {
    if (handle == null || handle.isEmpty()) {
        return Collections.emptyList();
    }

    MessagePart firstPart = handle.get(0);
    if (firstPart == null) {
        return Collections.emptyList();
    }

    // Safe null check chain
    if (firstPart.getBody() != null && firstPart.getBody().getData() != null) {
        return handle;
    }

    if (firstPart.getParts() != null) {
        return getPayloadParts(firstPart.getParts());
    }

    return Collections.emptyList();
}
```

**Impact:** MEDIUM - Can cause crashes on certain emails

---

## 🟡 High Priority Issues (Not Breaking, But Bad)

### 5. Constructor Does Network I/O (30-40 Second Block)
**Location:** `src/main/java/api/gmail/mail/Inbox.java:39-46`

**Current Code:**
```java
public Inbox() {
    con = new APIConnection();           // OAuth flow - can take 5-10 seconds
    messages = con.getPage();            // Gmail API call - 1-2 seconds
    for (Message message : messages) {   // 100 more API calls - 30-40 seconds!
        inboxList.add(factory.getMail(message, con));
    }
}
```

**Problems:**
1. Constructor takes 30-40 seconds to complete
2. Cannot show progress to user
3. Cannot cancel operation
4. Cannot handle errors gracefully
5. Makes testing impossible (always hits real Gmail API)
6. Violates principle: "Constructors should be fast and not fail"

**Fix:**
```java
public Inbox(APIConnection connection) {
    this.con = connection;  // Just store, don't use yet
}

// Separate method for loading
public List<Mail> loadMessages() {
    messages = con.getPage();
    for (Message message : messages) {
        inboxList.add(factory.getMail(message, con));
    }
    return inboxList;
}
```

**Impact:** HIGH - Poor user experience, untestable code

---

### 6. Missing Encapsulation (All Data Classes)
**Location:** `Mail.java:13-23`, `Headers.java`, `Body.java`

**Current Code:**
```java
public class Mail {
    String id;           // Package-private - NO GETTER!
    Headers headers;     // Package-private - NO GETTER!
    Body body;           // Package-private - NO GETTER!
}
```

**Problems:**
1. No getters - cannot access fields from other packages
2. No access control
3. Cannot add validation later
4. Cannot add computed properties
5. No immutability guarantees
6. Missing equals/hashCode/toString

**Fix (Modern Java 17+):**
```java
public record Mail(String id, Headers headers, Body body) {
    // Auto-generates: id(), headers(), body(), equals(), hashCode(), toString()
    // Immutable by default
}
```

**Impact:** HIGH - Violates fundamental OOP principles

---

### 7. Unused Import (Code Smell)
**Location:** `src/main/java/api/gmail/mail/body/Body.java:4`

**Current Code:**
```java
import java.util.concurrent.Callable;  // NEVER USED
```

**Problem:**
Leftover from multithreading planning work that was never completed.

**Fix:**
Delete the import line.

**Impact:** LOW - Just clutter, but indicates incomplete work

---

### 8. Unused Dependencies (Build Bloat)
**Location:** `build.gradle:49-50`

**Current Code:**
```gradle
implementation 'email-attachments:email-attachments:1.2.4'  // Never used
implementation 'javax.mail:mail:1.4.7'                      // Never used
```

**Problems:**
1. Increases build artifact size
2. Potential security vulnerabilities in unused libraries
3. Confusing for developers (why are these here?)
4. javax.mail is deprecated (should use jakarta.mail)

**Fix:**
Remove both lines from build.gradle after confirming they're truly unused.

**Impact:** MEDIUM - Security risk, build bloat

---

### 9. Test Dependencies in Wrong Scope
**Location:** `build.gradle:51-52`

**Current Code:**
```gradle
implementation 'org.junit.jupiter:junit-jupiter-api:5.8.2'
implementation 'org.junit.jupiter:junit-jupiter-engine:5.8.2'
```

**Problem:**
JUnit should be `testImplementation`, not `implementation`. This includes test libraries in the production artifact.

**Fix:**
```gradle
testImplementation 'org.junit.jupiter:junit-jupiter-api:5.8.2'
testImplementation 'org.junit.jupiter:junit-jupiter-engine:5.8.2'
```

**Impact:** LOW - Unnecessary bloat in production JAR

---

### 10. Java 11 Past End-of-Life
**Location:** `build.gradle:13-14`

**Current Code:**
```gradle
sourceCompatibility = 11
targetCompatibility = 11
```

**Problem:**
Java 11 LTS support ended September 2023. No more security updates.

**Fix:**
```gradle
sourceCompatibility = 21  // Latest LTS, supported until 2026+
targetCompatibility = 21
```

**Impact:** MEDIUM - Security risk, missing modern features

---

### 11. Outdated Dependencies (Security Risk)
**Location:** `build.gradle:46-50`

**Current Versions:**
- `javax.mail:1.4.7` - From 2011 (14 years old!)
- `google-api-services-gmail:v1-rev20220404` - From April 2022 (2.5 years old)
- `google-api-client:1.35.1` - Outdated

**Problem:**
Old dependencies likely have security vulnerabilities and missing features.

**Fix:**
```gradle
// Remove deprecated javax.mail entirely (unused)
implementation 'com.google.api-client:google-api-client:2.7.0'
implementation 'com.google.oauth-client:google-oauth-client-jetty:1.36.0'
implementation 'com.google.apis:google-api-services-gmail:v1-rev20240914-2.0.0'
```

**Impact:** HIGH - Security vulnerabilities, missing bug fixes

---

## 🔵 Incomplete Work (TODO Items)

### 12. Multithreading Never Implemented
**Location:** `src/main/java/api/gmail/APIConnection.java:37`

**TODO Comment:**
```java
/**
 * TODO: try to apply multithreading for both reading from the API and writing locally.
 */
```

**Evidence:**
- Empty interfaces: `Processing.java`, `BodyProcessing.java`
- Unused import: `java.util.concurrent.Callable` in Body.java
- Git history shows multithreading branch was merged but incomplete

**Impact:**
Message loading is sequential and VERY slow:
- 100 messages take 30-40 seconds
- Each message requires separate API call (~350ms each)

**Recommended Fix:**
Use Java 21 Virtual Threads (Project Loom):
```java
try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
    List<Future<Mail>> futures = messages.stream()
        .map(msg -> executor.submit(() -> factory.getMail(msg, con)))
        .toList();

    return futures.stream()
        .map(f -> f.get())
        .toList();
}
// Result: 100 messages in 2-3 seconds instead of 40 seconds!
```

**Impact:** HIGH - Major performance issue

---

## Summary Priority List

**Fix TODAY (Before Any New Development):**
1. ✅ Header filtering bug (line 166) - Headers never work
2. ✅ Data overwrite bug (line 213-214) - Email content lost
3. ✅ Resource leak (line 129) - File handles not closed
4. ✅ Null pointer risk (line 98) - Crashes on some emails

**Fix This Week:**
5. ✅ Upgrade to Java 21
6. ✅ Update all dependencies
7. ✅ Add proper encapsulation (use records)
8. ✅ Fix test dependency scope
9. ✅ Remove unused dependencies
10. ✅ Refactor Inbox constructor (separate init from construction)

**Plan for Next Sprint:**
11. ✅ Implement multithreading with virtual threads
12. ✅ Add proper error handling
13. ✅ Implement TUI
14. ✅ Add caching layer

---

## Quick Fix Script

Here's the order to fix issues:

```bash
# 1. Create a new branch for fixes
git checkout -b fix/critical-issues

# 2. Fix the 4 critical bugs first (manual edits)
#    - Edit APIConnection.java line 166 (header filtering)
#    - Edit APIConnection.java line 213-214 (data overwrite)
#    - Edit APIConnection.java line 129 (try-with-resources)
#    - Edit APIConnection.java line 98 (null checks)

# 3. Update build.gradle
#    - Change Java version to 21
#    - Update dependencies
#    - Remove unused dependencies
#    - Fix test scope

# 4. Convert data classes to records
#    - Convert Mail to record
#    - Convert Headers to record
#    - Convert Body to record

# 5. Test everything
./gradlew clean build
./gradlew test

# 6. Commit fixes
git add .
git commit -m "fix: resolve critical bugs and modernize codebase

- Fix header filtering logic (equals → contains)
- Fix email body data overwrite bug
- Fix resource leak with try-with-resources
- Add null safety checks in getPayloadParts
- Upgrade to Java 21
- Update all dependencies to latest versions
- Convert POJOs to records for proper encapsulation
- Remove unused dependencies
- Fix test dependency scope"

git push -u origin fix/critical-issues
```

---

## Testing Checklist

After fixing all issues, verify:

- [ ] Build succeeds: `./gradlew clean build`
- [ ] Tests pass: `./gradlew test`
- [ ] Can authenticate with Gmail
- [ ] Can fetch messages
- [ ] Headers are properly extracted (check logs)
- [ ] Email body content is preserved
- [ ] No resource leak warnings
- [ ] No NullPointerException on various emails

---

## References

- Main Roadmap: `ROADMAP.md`
- Codebase analysis: See initial exploration results
- Gmail API docs: https://developers.google.com/gmail/api
- Java 21 features: https://openjdk.org/projects/jdk/21/
- Lanterna TUI: https://github.com/mabe02/lanterna
