# Gmail TUI Application - Comprehensive Roadmap

**Generated:** 2025-11-13
**Current Status:** Early development - basic Gmail API integration functional, TUI not implemented
**Target:** Modern Java TUI application for browsing Gmail emails

---

## Table of Contents
1. [Critical Code Quality Issues](#critical-code-quality-issues)
2. [Java Bad Habits & Anti-patterns](#java-bad-habits--anti-patterns)
3. [Modernization Strategy](#modernization-strategy)
4. [Architecture Roadmap](#architecture-roadmap)
5. [TUI Implementation Plan](#tui-implementation-plan)
6. [GCP Integration Opportunities](#gcp-integration-opportunities)
7. [Implementation Phases](#implementation-phases)

---

## Critical Code Quality Issues

### 🔴 CRITICAL BUGS (Fix Immediately)

#### 1. Header Filtering Logic Bug (APIConnection.java:166)
```java
// CURRENT (BROKEN):
if (header.getName().equals("Delivered-To|Date|From|To|Subject"))
    selectedHeaders.put(header.getName(), header.getValue());

// ISSUE: equals() compares exact string, will NEVER match individual header names
// RESULT: No headers ever get selected despite the loop

// FIX:
Set<String> wantedHeaders = Set.of("Delivered-To", "Date", "From", "To", "Subject");
if (wantedHeaders.contains(header.getName())) {
    selectedHeaders.put(header.getName(), header.getValue());
}
```
**Impact:** Headers are never properly extracted from emails
**File:** `src/main/java/api/gmail/APIConnection.java:161-170`

#### 2. Data Overwrite Bug (APIConnection.java:213-214)
```java
// CURRENT (BROKEN):
data.put(parts.indexOf(part), decode(part.getBody().getData())); // Line 213
data.put(parts.indexOf(part), part.getMimeType());               // Line 214 - OVERWRITES!

// ISSUE: Second put() overwrites the decoded body data with MIME type
// RESULT: Lose actual email content, only keep MIME type string

// FIX: Use proper data structure
record EmailPart(String content, String mimeType) {}
Map<Integer, EmailPart> data = new HashMap<>();
data.put(parts.indexOf(part), new EmailPart(decode(part.getBody().getData()), part.getMimeType()));
```
**Impact:** Email body content is lost, only MIME type retained
**File:** `src/main/java/api/gmail/APIConnection.java:208-220`

#### 3. Resource Leak (APIConnection.java:129)
```java
// CURRENT (LEAKS):
InputStream in = APIConnection.class.getResourceAsStream("/credentials.json");
// ... use stream but never close it

// FIX:
try (InputStream in = APIConnection.class.getResourceAsStream("/credentials.json")) {
    if (in == null) throw new FileNotFoundException("Resource not found: /credentials.json");
    GoogleClientSecrets clientSecrets = GoogleClientSecrets.load(JSON_FACTORY, new InputStreamReader(in));
    // ...
}
```
**Impact:** Resource leak, file handles not released
**File:** `src/main/java/api/gmail/APIConnection.java:126-137`

#### 4. Unsafe Null Handling (APIConnection.java:98)
```java
// CURRENT (NPE RISK):
if (handle.get(0).getBody().getData() != null) return handle;

// ISSUE: If handle is empty list, get(0) throws IndexOutOfBoundsException
// If handle.get(0).getBody() is null, NullPointerException

// FIX:
if (handle.isEmpty()) return Collections.emptyList();
MessagePart firstPart = handle.get(0);
if (firstPart.getBody() != null && firstPart.getBody().getData() != null) {
    return handle;
}
```
**Impact:** Potential crashes on malformed email messages
**File:** `src/main/java/api/gmail/APIConnection.java:96-101`

---

## Java Bad Habits & Anti-patterns

### 🟡 Code Quality Issues

#### 1. Missing Encapsulation (Multiple Files)
**Bad Practice:**
```java
// Mail.java, Body.java, Headers.java - ALL have this issue
public class Mail {
    String id;           // Package-private, no getter
    Headers headers;     // Package-private, no getter
    Body body;           // Package-private, no getter
}
```

**Why It's Bad:**
- Violates core OOP principle of encapsulation
- Cannot enforce invariants or validation
- Difficult to refactor or add behavior later
- Cannot add lazy loading, caching, or computed properties
- Breaking change to add getters later

**Modern Fix (Java 17+):**
```java
// Use records for immutable data
public record Mail(String id, Headers headers, Body body) {
    // Automatic getters: id(), headers(), body()
    // Immutable by default
    // Equals/hashCode/toString generated
}
```

**Files Affected:**
- `src/main/java/api/gmail/mail/factory/Mail.java:13-23`
- `src/main/java/api/gmail/mail/body/Body.java`
- `src/main/java/api/gmail/mail/headers/Headers.java`

#### 2. Unused Imports and Dependencies
**Issues:**
```java
// Body.java:4
import java.util.concurrent.Callable; // NEVER USED - leftover from planning
```

**build.gradle:**
```gradle
implementation 'email-attachments:email-attachments:1.2.4'  // Not used anywhere
implementation 'javax.mail:mail:1.4.7'                       // Declared but unused
testImplementation 'org.junit.jupiter:junit-jupiter-api:5.8.2'  // Should be testImplementation
```

**Why It's Bad:**
- Increases build artifact size
- Confuses developers about actual dependencies
- Potential security vulnerabilities in unused libraries
- Slower build times

**Fix:** Remove all unused imports and dependencies after verification

#### 3. Poor Exception Handling
**Bad Practice:**
```java
// APIConnection.java - wraps all exceptions as runtime
} catch (IOException e) {
    throw new APIConnectionRuntimeException(e);
}
```

**Why It's Bad:**
- Loses context about what operation failed
- Cannot handle different error types differently
- No retry logic for transient failures
- Difficult to provide user-friendly error messages
- Logs at INFO level instead of ERROR/WARNING

**Modern Approach:**
```java
public sealed interface GmailOperationResult<T> {
    record Success<T>(T data) implements GmailOperationResult<T> {}
    record Failure<T>(Exception error, String context) implements GmailOperationResult<T> {}
    record RateLimited<T>(Instant retryAfter) implements GmailOperationResult<T> {}
}

// Usage with pattern matching (Java 21)
GmailOperationResult<List<Message>> result = fetchMessages();
switch (result) {
    case Success(var messages) -> processMessages(messages);
    case Failure(var error, var context) -> logger.error("Failed: {}", context, error);
    case RateLimited(var retryAfter) -> scheduleRetry(retryAfter);
}
```

#### 4. Static Utility Class Misuse
**Current Pattern:**
```java
public class APIConnection {
    private static final Logger logger = ...;
    private static final NetHttpTransport HTTP_TRANSPORT;
    // Mix of static and instance methods
}
```

**Why It's Bad:**
- Difficult to test (cannot mock static methods easily)
- Hard to swap implementations
- Global state issues
- Cannot use dependency injection
- Singleton pattern hidden in static initializer

**Modern Approach:**
```java
// Use dependency injection with interfaces
public interface GmailService {
    List<Message> fetchMessages(int limit);
    Message getMessage(String messageId);
}

@Service // Using Spring or similar DI framework
public class GmailServiceImpl implements GmailService {
    private final Gmail gmailClient;
    private final CredentialManager credentialManager;

    public GmailServiceImpl(Gmail gmailClient, CredentialManager credentialManager) {
        this.gmailClient = gmailClient;
        this.credentialManager = credentialManager;
    }
}
```

#### 5. Constructor Does Too Much Work
**Bad Practice:**
```java
// Inbox.java:39-46
public Inbox() {
    con = new APIConnection();        // Network call in OAuth flow
    messages = con.getPage();         // Network call to Gmail API
    for (Message message : messages) {
        inboxList.add(factory.getMail(message, con));  // 100 more network calls!
    }
}
```

**Why It's Bad:**
- Constructor can take 30-60 seconds to complete
- Cannot handle errors gracefully during construction
- Cannot show progress to user
- Violates Single Responsibility Principle
- Makes testing impossible (always hits real API)
- Constructor should never fail

**Modern Approach:**
```java
public class Inbox {
    private final GmailService gmailService;
    private final MessageCache messageCache;

    public Inbox(GmailService gmailService, MessageCache messageCache) {
        this.gmailService = gmailService;
        this.messageCache = messageCache;
    }

    // Separate initialization from construction
    public CompletableFuture<List<Mail>> loadMessages(int pageSize) {
        return CompletableFuture.supplyAsync(() -> {
            // Load messages asynchronously with progress callbacks
        });
    }
}
```

#### 6. No Input Validation
**Issues:**
```java
public List<Message> getPage(String token) {
    // No validation that token is valid format
    // No null check
    // No empty string check
}
```

**Modern Fix:**
```java
public List<Message> getPage(String token) {
    Objects.requireNonNull(token, "Page token cannot be null");
    if (token.isBlank()) {
        throw new IllegalArgumentException("Page token cannot be empty");
    }
    // Optionally validate token format
    // ...
}
```

#### 7. Missing Defensive Null Checks
**Issue:**
```java
// APIConnection.java:211
if (parts != null) {
    for (MessagePart part : parts) {
        // What if part is null?
        // What if part.getBody() is null?
        // What if part.getBody().getData() is null?
    }
}
```

**Modern Fix (with Java 8+ Optional):**
```java
Optional.ofNullable(parts)
    .orElse(Collections.emptyList())
    .stream()
    .filter(part -> part.getBody() != null && part.getBody().getData() != null)
    .forEach(part -> processMessagePart(part));
```

---

## Modernization Strategy

### Java Version Upgrade Path

#### Current State
- **Using:** Java 11 (LTS, EOL: September 2023 - PAST END OF LIFE!)
- **Available in environment:** Java 21.0.8 (Latest LTS)

#### Upgrade Benefits

**Java 17 Features (LTS - September 2021):**
- **Records:** Perfect for immutable data classes (Mail, Headers, Body)
- **Sealed Classes:** Type-safe result types
- **Pattern Matching for instanceof:** Cleaner type checks
- **Text Blocks:** Better for multi-line strings (SQL, JSON templates)
- **Switch Expressions:** More concise conditional logic

**Java 21 Features (LTS - September 2023):**
- **Pattern Matching for switch:** Powerful type-safe switching
- **Virtual Threads (Project Loom):** Perfect for I/O-heavy Gmail API calls
- **Record Patterns:** Destructuring in pattern matching
- **String Templates (Preview):** Type-safe string formatting
- **Sequenced Collections:** Predictable collection ordering

#### Recommended Upgrade: Java 21

**Rationale:**
1. Java 11 is past EOL (no security updates)
2. Java 21 is latest LTS (supported until September 2026+)
3. Virtual Threads solve your multithreading TODO perfectly
4. Records eliminate boilerplate in Mail/Headers/Body
5. Pattern matching simplifies error handling

**Migration Effort:** LOW
- Current code uses no Java 11-specific features
- No breaking changes from 11 → 21
- Gradle already configured correctly
- Simple change: `sourceCompatibility = 21`, `targetCompatibility = 21`

### Dependency Modernization

#### Critical Updates Needed

| Dependency | Current Version | Latest Version | Issues | Priority |
|------------|----------------|----------------|--------|----------|
| javax.mail | 1.4.7 (2011) | jakarta.mail 2.0.1 | Deprecated namespace, 13 years old | HIGH |
| google-api-services-gmail | v1-rev20220404 | v1-rev20240914 | 2.5 years outdated | HIGH |
| google-api-client | 1.35.1 | 2.7.0 | Missing security fixes | HIGH |
| google-oauth-client-jetty | 1.34.1 | 1.36.0 | Minor update | MEDIUM |
| JUnit | 5.8.2 | 5.11.3 | Missing new features | LOW |
| Gradle | 7.5 | 8.11.1 | Still works but outdated | LOW |

#### Recommended New Dependencies

**1. Modern HTTP Client (Replace legacy Google HTTP)**
```gradle
implementation 'com.google.http-client:google-http-client:1.45.1'
implementation 'com.google.http-client:google-http-client-gson:1.45.1'
```

**2. Logging Framework (Replace java.util.logging)**
```gradle
implementation 'org.slf4j:slf4j-api:2.0.16'
implementation 'ch.qos.logback:logback-classic:1.5.12'
```

**Why SLF4J/Logback:**
- Industry standard (used by 90% of Java projects)
- Better performance than java.util.logging
- Structured logging support (JSON output)
- Async logging for better performance
- Rich configuration options
- Integration with modern observability tools

**3. Dependency Injection (NEW)**
```gradle
// Option A: Lightweight - Google Guice
implementation 'com.google.inject:guice:7.0.0'

// Option B: Full-featured - Spring Boot (recommended for GCP)
implementation 'org.springframework.boot:spring-boot-starter:3.4.0'
implementation 'org.springframework.boot:spring-boot-starter-logging:3.4.0'
```

**4. Configuration Management (NEW)**
```gradle
implementation 'com.typesafe:config:1.4.3'
// Or if using Spring Boot:
implementation 'org.springframework.boot:spring-boot-starter-config'
```

**5. Caching Layer (NEW)**
```gradle
implementation 'com.github.ben-manes.caffeine:caffeine:3.1.8'
// Or Redis for distributed caching:
implementation 'org.springframework.boot:spring-boot-starter-data-redis:3.4.0'
```

**6. TUI Framework (NEW - See TUI section)**
```gradle
// Option A: Lanterna (recommended)
implementation 'com.googlecode.lanterna:lanterna:3.1.2'

// Option B: JLine3 (more REPL-focused)
implementation 'org.jline:jline:3.27.1'

// Option C: Text-IO (modern, declarative)
implementation 'org.beryx:text-io:3.4.1'
```

**7. Testing (Enhance existing)**
```gradle
testImplementation 'org.junit.jupiter:junit-jupiter:5.11.3'
testImplementation 'org.mockito:mockito-core:5.14.2'
testImplementation 'org.mockito:mockito-junit-jupiter:5.14.2'
testImplementation 'org.assertj:assertj-core:3.27.0'  // Better assertions
```

**8. Utilities**
```gradle
implementation 'org.apache.commons:commons-lang3:3.17.0'
implementation 'com.google.guava:guava:33.3.1-jre'
```

#### Dependencies to REMOVE
```gradle
// REMOVE - never used
implementation 'email-attachments:email-attachments:1.2.4'

// MOVE to testImplementation scope
testImplementation 'org.junit.jupiter:junit-jupiter-api:5.8.2'
testImplementation 'org.junit.jupiter:junit-jupiter-engine:5.8.2'
```

### Modern Code Patterns

#### Pattern 1: Replace POJOs with Records

**Before (Current):**
```java
// Mail.java - 37 lines
public class Mail {
    String id;
    Headers headers;
    Body body;

    public Mail(String id, Headers headers, Body body) {
        this.headers = headers;
        this.body = body;
        this.id = id;
    }
    // No getters, equals, hashCode, toString
}
```

**After (Java 17+):**
```java
// 1 line!
public record Mail(String id, Headers headers, Body body) {
    // Auto-generated: id(), headers(), body(), equals(), hashCode(), toString()

    // Optional: Add validation
    public Mail {
        Objects.requireNonNull(id, "id cannot be null");
        Objects.requireNonNull(headers, "headers cannot be null");
        Objects.requireNonNull(body, "body cannot be null");
    }
}
```

**Benefits:**
- 97% less code (37 lines → 1 line)
- Immutable by default
- Proper equals/hashCode implementation
- Better toString for debugging
- Pattern matching support

#### Pattern 2: Virtual Threads for API Calls (Java 21)

**Current Problem:**
```java
// Inbox.java - sequential processing, takes 30-40 seconds for 100 emails
for (Message message : messages) {
    inboxList.add(factory.getMail(message, con));  // Each call takes ~350ms
}
```

**Modern Solution with Virtual Threads:**
```java
public class Inbox {
    private final GmailService gmailService;
    private final ExecutorService virtualThreadExecutor;

    public Inbox(GmailService gmailService) {
        this.gmailService = gmailService;
        // Virtual threads - can spawn millions without overhead
        this.virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    public List<Mail> loadMessages(List<Message> messages) {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Mail>> futures = messages.stream()
                .map(message -> executor.submit(() ->
                    factory.getMail(message, gmailService)))
                .toList();

            return futures.stream()
                .map(future -> {
                    try {
                        return future.get();
                    } catch (Exception e) {
                        logger.error("Failed to load message", e);
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .toList();
        }
    }
}
```

**Benefits:**
- 100 emails: 40 seconds → 2-3 seconds (13x faster!)
- No thread pool tuning needed
- Millions of concurrent tasks possible
- Perfect for I/O-bound workloads like API calls
- Directly addresses TODO comment at APIConnection.java:37

#### Pattern 3: Result Types for Error Handling

**Current Problem:**
```java
// All errors wrapped as RuntimeException, lose context
public String getBody(Message message) {
    return decode(getMessageHandle(message).getPayload().getBody().getData());
    // What if network fails? Auth expires? Message deleted? Rate limited?
}
```

**Modern Solution (Java 17+):**
```java
public sealed interface Result<T> {
    record Success<T>(T value) implements Result<T> {}
    record Failure<T>(Exception error, String context) implements Result<T> {}
}

public Result<String> getBody(Message message) {
    try {
        Message handle = getMessageHandle(message);
        String body = decode(handle.getPayload().getBody().getData());
        return new Result.Success<>(body);
    } catch (IOException e) {
        return new Result.Failure<>(e, "Failed to fetch message body for: " + message.getId());
    }
}

// Usage with pattern matching (Java 21)
Result<String> result = connection.getBody(message);
String body = switch (result) {
    case Success(var value) -> value;
    case Failure(var error, var context) -> {
        logger.error(context, error);
        yield "[Error loading message body]";
    }
};
```

#### Pattern 4: Builder Pattern for Complex Objects

**Use Case:** Gmail API query construction
```java
public class GmailQuery {
    private final String query;
    private final int maxResults;
    private final List<String> labelIds;
    private final boolean includeSpamTrash;

    private GmailQuery(Builder builder) {
        this.query = builder.query;
        this.maxResults = builder.maxResults;
        this.labelIds = List.copyOf(builder.labelIds);
        this.includeSpamTrash = builder.includeSpamTrash;
    }

    public static class Builder {
        private String query = "";
        private int maxResults = 100;
        private List<String> labelIds = new ArrayList<>();
        private boolean includeSpamTrash = false;

        public Builder query(String query) {
            this.query = query;
            return this;
        }

        public Builder maxResults(int maxResults) {
            this.maxResults = maxResults;
            return this;
        }

        public Builder labelIds(String... labelIds) {
            this.labelIds = Arrays.asList(labelIds);
            return this;
        }

        public Builder includeSpamTrash(boolean include) {
            this.includeSpamTrash = include;
            return this;
        }

        public GmailQuery build() {
            return new GmailQuery(this);
        }
    }
}

// Usage
GmailQuery query = new GmailQuery.Builder()
    .query("from:boss@company.com")
    .maxResults(50)
    .labelIds("INBOX", "IMPORTANT")
    .build();
```

---

## Architecture Roadmap

### Current Architecture (Flat, Procedural)
```
┌─────────────┐
│    main()   │
└──────┬──────┘
       │
       v
┌─────────────────┐       ┌──────────────────┐
│     Inbox       │──────>│  APIConnection   │──────> Gmail API
│  (constructor   │       │ (static utility) │
│   does work)    │       └──────────────────┘
└─────────────────┘
       │
       v
┌─────────────────┐
│  MailFactory    │
└─────────────────┘
       │
       v
┌─────────────────┐
│      Mail       │
│   (no getters)  │
└─────────────────┘
```

**Problems:**
- No separation of concerns
- Cannot test without hitting real Gmail API
- No caching, every operation is API call
- Constructor blocks for 30+ seconds
- No way to show progress to user
- Tightly coupled components

### Target Architecture (Modern, Layered)

```
┌──────────────────────────────────────────────────────────┐
│                      TUI Layer                           │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  │
│  │ Email List   │  │ Email Detail │  │ Search View  │  │
│  │ View         │  │ View         │  │              │  │
│  └──────────────┘  └──────────────┘  └──────────────┘  │
└─────────────────────┬────────────────────────────────────┘
                      │ Lanterna Events
                      v
┌──────────────────────────────────────────────────────────┐
│                  Application Layer                       │
│  ┌──────────────────────────────────────────────────┐   │
│  │          InboxController                          │   │
│  │  - Handles user actions                          │   │
│  │  - Coordinates views and services                │   │
│  │  - Progress callbacks                            │   │
│  └───────────────────┬──────────────────────────────┘   │
└────────────────────────┼────────────────────────────────┘
                         │
                         v
┌──────────────────────────────────────────────────────────┐
│                   Service Layer                          │
│  ┌─────────────────┐  ┌──────────────┐  ┌────────────┐ │
│  │  GmailService   │  │ SearchService│  │ CacheService│ │
│  │  (Interface)    │  │              │  │            │ │
│  └────────┬────────┘  └──────────────┘  └────────────┘ │
└───────────┼──────────────────────────────────────────────┘
            │
            v
┌──────────────────────────────────────────────────────────┐
│               Repository Layer                           │
│  ┌─────────────────────────────────────────────────┐    │
│  │  GmailRepository                                 │    │
│  │  - Caches messages (Caffeine/Redis)             │    │
│  │  - Batch operations                             │    │
│  │  - Rate limiting                                │    │
│  └──────────────────┬──────────────────────────────┘    │
└────────────────────┼─────────────────────────────────────┘
                     │
                     v
┌──────────────────────────────────────────────────────────┐
│                 Integration Layer                        │
│  ┌─────────────────────────────────────────────────┐    │
│  │  GmailApiClient                                  │    │
│  │  - Gmail API wrapper                            │    │
│  │  - OAuth management                             │    │
│  │  - Retry logic                                  │    │
│  │  - Error mapping                                │    │
│  └──────────────────┬──────────────────────────────┘    │
└────────────────────┼─────────────────────────────────────┘
                     │
                     v
              ┌──────────────┐
              │  Gmail API   │
              └──────────────┘
```

### Proposed Package Structure

```
api.gmail/
├── Main.java                                    # Application entry point
├── config/                                      # Configuration
│   ├── AppConfig.java                          # Main config class
│   ├── GmailConfig.java                        # Gmail API config
│   └── CacheConfig.java                        # Cache configuration
├── domain/                                      # Domain models
│   ├── Mail.java                               # Record: email representation
│   ├── Headers.java                            # Record: email headers
│   ├── Body.java                               # Record: email body
│   ├── Attachment.java                         # Record: attachment info
│   ├── Label.java                              # Record: Gmail label
│   └── SearchQuery.java                        # Record: search criteria
├── repository/                                  # Data access layer
│   ├── GmailRepository.java                    # Interface
│   ├── GmailRepositoryImpl.java                # Implementation
│   └── cache/
│       ├── MessageCache.java                   # Cache interface
│       └── CaffeineMessageCache.java           # In-memory cache
├── service/                                     # Business logic
│   ├── GmailService.java                       # Interface
│   ├── GmailServiceImpl.java                   # Implementation
│   ├── SearchService.java                      # Email search
│   └── AuthenticationService.java              # OAuth management
├── client/                                      # External API integration
│   ├── GmailApiClient.java                     # Gmail API wrapper
│   ├── oauth/
│   │   ├── CredentialManager.java              # OAuth flow
│   │   └── TokenRefreshHandler.java            # Token refresh
│   └── retry/
│       ├── RetryPolicy.java                    # Retry strategy
│       └── RateLimiter.java                    # Rate limiting
├── ui/                                          # TUI layer
│   ├── TuiApplication.java                     # Main TUI app
│   ├── controller/
│   │   ├── InboxController.java                # Inbox logic
│   │   ├── DetailController.java               # Email detail logic
│   │   └── SearchController.java               # Search logic
│   ├── view/
│   │   ├── InboxView.java                      # Email list view
│   │   ├── DetailView.java                     # Email detail view
│   │   ├── SearchView.java                     # Search view
│   │   └── components/
│   │       ├── EmailListPanel.java             # Reusable list
│   │       ├── EmailDetailPanel.java           # Reusable detail
│   │       └── StatusBar.java                  # Status bar
│   └── util/
│       ├── KeyBindings.java                    # Keyboard shortcuts
│       └── Colors.java                         # Color scheme
├── exception/                                   # Custom exceptions
│   ├── GmailException.java                     # Base exception
│   ├── AuthenticationException.java            # Auth errors
│   ├── RateLimitException.java                 # Rate limit errors
│   └── MessageNotFoundException.java           # Not found errors
└── util/                                        # Utilities
    ├── Result.java                             # Result type (sealed)
    ├── DateFormatter.java                      # Date utilities
    └── TextUtils.java                          # Text processing
```

### Dependency Injection Setup

**Option A: Google Guice (Lightweight)**
```java
// Main.java
public class Main {
    public static void main(String[] args) {
        Injector injector = Guice.createInjector(
            new GmailModule(),
            new CacheModule(),
            new TuiModule()
        );

        TuiApplication app = injector.getInstance(TuiApplication.class);
        app.start();
    }
}

// GmailModule.java
public class GmailModule extends AbstractModule {
    @Override
    protected void configure() {
        bind(GmailService.class).to(GmailServiceImpl.class);
        bind(GmailRepository.class).to(GmailRepositoryImpl.class);
        bind(MessageCache.class).to(CaffeineMessageCache.class);
    }

    @Provides
    @Singleton
    Gmail provideGmailClient(CredentialManager credentialManager) {
        return new Gmail.Builder(
            GoogleNetHttpTransport.newTrustedTransport(),
            GsonFactory.getDefaultInstance(),
            credentialManager.getCredentials()
        ).setApplicationName("Gmail TUI").build();
    }
}
```

**Option B: Spring Boot (Full-featured, better for GCP)**
```java
@SpringBootApplication
public class GmailTuiApplication {
    public static void main(String[] args) {
        SpringApplication.run(GmailTuiApplication.class, args);
    }

    @Bean
    CommandLineRunner run(TuiApplication tuiApp) {
        return args -> tuiApp.start();
    }
}

// Service annotation-based DI
@Service
public class GmailServiceImpl implements GmailService {
    private final GmailRepository repository;

    public GmailServiceImpl(GmailRepository repository) {
        this.repository = repository;
    }
}
```

---

## TUI Implementation Plan

### TUI Framework Comparison

| Framework | Pros | Cons | Best For |
|-----------|------|------|----------|
| **Lanterna** | Full TUI toolkit, component-based, works everywhere | Learning curve, verbose | Complex UIs like email client |
| **JLine3** | Great REPL support, tab completion | Limited layout options | CLI tools, shells |
| **Text-IO** | Declarative API, easy to learn | Less flexible layouts | Wizards, forms |
| **JCurses** | Lightweight | Unmaintained since 2010 | ❌ Don't use |

**Recommendation: Lanterna 3.1.2**

**Reasons:**
- Mature, actively maintained
- Pure Java, no native dependencies
- Works in any terminal (Windows/Mac/Linux)
- Component-based (panels, buttons, text boxes)
- Event-driven architecture
- Supports mouse and keyboard
- Good documentation
- Perfect for email client UI

### TUI Design - Wire frames

#### 1. Main Inbox View
```
┌─ Gmail TUI ─────────────────────────────────────────────────────────┐
│ Inbox (142 messages) | 12 unread                     ⟨?⟩ Help       │
├─────────────────────────────────────────────────────────────────────┤
│ ┌─ Filters ──────────────────────────────────────────────────────┐ │
│ │ [INBOX▾] [Unread Only☐] Search: [________________] [🔍]        │ │
│ └─────────────────────────────────────────────────────────────────┘ │
├─────────────────────────────────────────────────────────────────────┤
│ ┏━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┓ │
│ ┃ ●  Nov 13  Boss McBossface        RE: Important Project       ┃ │ <- Selected
│ ┣━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┫ │
│ ┃    Can you send me the TPS reports by EOD? Thanks...          ┃ │
│ ┗━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┛ │
│    Nov 13  GitHub                   [Alert] Build failed          │
│             Your PR #123 build has failed. Click here to...       │
│ ●  Nov 12  Newsletter Weekly        This Week in Tech             │
│             Top stories this week: AI advances, new...            │
│    Nov 12  John Doe                 Lunch plans?                  │
│             Hey, want to grab lunch tomorrow? Let me know...      │
│    Nov 11  HR Department            Benefits Reminder             │
│             Don't forget to update your benefits by...            │
│                                                                     │
├─────────────────────────────────────────────────────────────────────┤
│ Page 1 of 15 | ↑↓: Navigate | Enter: Open | s: Search | q: Quit   │
└─────────────────────────────────────────────────────────────────────┘

Legend:
  ● = Unread
  ⭐ = Starred
  📎 = Has attachment
```

#### 2. Email Detail View
```
┌─ Email Detail ──────────────────────────────────────────────────────┐
│ ← Back to Inbox                                    ⟨?⟩ Help         │
├─────────────────────────────────────────────────────────────────────┤
│ From:    Boss McBossface <boss@company.com>                         │
│ To:      Me <me@company.com>                                        │
│ Date:    Nov 13, 2025 2:34 PM                                       │
│ Subject: RE: Important Project                                      │
│ Labels:  [INBOX] [IMPORTANT] [CATEGORY_WORK]                        │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│ Hi there,                                                           │
│                                                                     │
│ Can you send me the TPS reports by EOD today? We need them for     │
│ the client meeting tomorrow morning.                               │
│                                                                     │
│ Also, please include the Q4 projections.                           │
│                                                                     │
│ Thanks,                                                             │
│ Boss                                                                │
│                                                                     │
│ ┌─ Attachments (2) ──────────────────────────────────────────────┐ │
│ │ 📎 tps-report-template.xlsx (45 KB)                            │ │
│ │ 📎 meeting-agenda.pdf (120 KB)                                 │ │
│ └─────────────────────────────────────────────────────────────────┘ │
│                                                                     │
│                                                                     │
├─────────────────────────────────────────────────────────────────────┤
│ r: Reply | a: Reply All | f: Forward | d: Download | ESC: Back     │
└─────────────────────────────────────────────────────────────────────┘
```

#### 3. Search View
```
┌─ Search ────────────────────────────────────────────────────────────┐
│ Search Gmail                                       ⟨?⟩ Help         │
├─────────────────────────────────────────────────────────────────────┤
│ Query: [from:boss@company.com subject:project_______________]       │
│                                                                     │
│ ┌─ Search Operators ─────────────────────────────────────────────┐ │
│ │ from:user@example.com    - From specific sender               │ │
│ │ to:user@example.com      - To specific recipient              │ │
│ │ subject:keywords         - In subject line                    │ │
│ │ has:attachment          - Has attachments                     │ │
│ │ is:unread               - Unread messages                     │ │
│ │ after:2025/01/01        - After date                          │ │
│ │ before:2025/12/31       - Before date                         │ │
│ └─────────────────────────────────────────────────────────────────┘ │
│                                                                     │
│ ┌─ Results (42 found) ───────────────────────────────────────────┐ │
│ │ ● Nov 13  Boss McBossface    RE: Important Project            │ │
│ │   Nov 11  Boss McBossface    Project Update                   │ │
│ │   Nov 08  Boss McBossface    FW: Project Timeline             │ │
│ │                                                               │ │
│ └─────────────────────────────────────────────────────────────────┘ │
├─────────────────────────────────────────────────────────────────────┤
│ Enter: Search | ESC: Cancel | ↑↓: Navigate results                 │
└─────────────────────────────────────────────────────────────────────┘
```

### Key Bindings

| Key | Action | Context |
|-----|--------|---------|
| `↑` / `k` | Navigate up | Email list |
| `↓` / `j` | Navigate down | Email list |
| `Enter` | Open selected email | Email list |
| `Space` | Toggle read/unread | Email list |
| `s` | Star/unstar | Email list |
| `/` | Search | Global |
| `r` | Reply | Email detail |
| `a` | Reply all | Email detail |
| `f` | Forward | Email detail |
| `d` | Download attachment | Email detail |
| `ESC` | Go back | Any view |
| `q` | Quit application | Global |
| `?` | Help screen | Global |
| `Ctrl+R` | Refresh inbox | Inbox view |
| `n` | Next page | Email list |
| `p` | Previous page | Email list |

### Lanterna Implementation Structure

```java
// TuiApplication.java
public class TuiApplication {
    private final Terminal terminal;
    private final Screen screen;
    private final WindowBasedTextGUI gui;
    private final InboxController inboxController;

    public TuiApplication(InboxController inboxController) throws IOException {
        this.inboxController = inboxController;
        this.terminal = new DefaultTerminalFactory().createTerminal();
        this.screen = new TerminalScreen(terminal);
        this.gui = new MultiWindowTextGUI(screen);
    }

    public void start() throws IOException {
        screen.startScreen();

        // Set up main window
        BasicWindow mainWindow = new BasicWindow("Gmail TUI");
        mainWindow.setHints(List.of(Window.Hint.FULL_SCREEN));

        // Build UI
        Panel contentPanel = new Panel(new BorderLayout());
        contentPanel.addComponent(createInboxView(), BorderLayout.Location.CENTER);
        contentPanel.addComponent(createStatusBar(), BorderLayout.Location.BOTTOM);

        mainWindow.setComponent(contentPanel);
        gui.addWindow(mainWindow);

        // Event loop
        gui.waitForWindowToClose(mainWindow);
        screen.stopScreen();
    }

    private Component createInboxView() {
        Panel panel = new Panel(new LinearLayout(Direction.VERTICAL));

        // Filter bar
        panel.addComponent(createFilterBar());

        // Email list
        ActionListBox emailList = new ActionListBox();
        List<Mail> emails = inboxController.getEmails();
        for (Mail email : emails) {
            emailList.addItem(formatEmailSummary(email), () -> showEmailDetail(email));
        }
        panel.addComponent(emailList.withBorder(Borders.singleLine("Inbox")));

        return panel;
    }

    private String formatEmailSummary(Mail email) {
        String unreadMarker = email.isUnread() ? "● " : "  ";
        String date = formatDate(email.headers().date());
        String from = truncate(email.headers().from(), 25);
        String subject = truncate(email.headers().subject(), 40);

        return String.format("%s%-8s  %-25s  %s", unreadMarker, date, from, subject);
    }
}
```

### Progress Indicators

**For long operations (loading inbox, search):**
```java
// Use Lanterna's WaitingDialog
WaitingDialog.showDialog(gui, "Loading Messages",
    "Fetching emails from Gmail...");

// Or custom progress bar
ProgressBar progressBar = new ProgressBar(0, 100);
// Update as messages load
progressBar.setValue((int) ((loaded / total) * 100));
```

---

## GCP Integration Opportunities

### Why Use GCP?

Since you're already using Gmail API (Google service), GCP integration provides:
1. **Unified billing** - One invoice for all Google services
2. **Better integration** - First-class Gmail API support
3. **Free tier** - Significant free quota for small projects
4. **Managed services** - Less operational overhead
5. **Security** - Secret Manager for credentials

### Recommended GCP Services

#### 1. Secret Manager (HIGH PRIORITY)

**Current Problem:**
```
/credentials.json in resources directory (gitignored)
tokens/ directory stores OAuth tokens in plain files
Hardcoded port 8888 for OAuth callback
```

**GCP Solution:**
```gradle
implementation 'com.google.cloud:google-cloud-secretmanager:2.54.0'
```

```java
// CredentialManager.java
@Service
public class GcpCredentialManager {
    private final SecretManagerServiceClient secretManager;

    public GoogleClientSecrets getClientSecrets() {
        String secretName = "projects/YOUR_PROJECT/secrets/gmail-credentials/versions/latest";
        AccessSecretVersionResponse response = secretManager.accessSecretVersion(secretName);
        String credentialsJson = response.getPayload().getData().toStringUtf8();

        return GoogleClientSecrets.load(
            GsonFactory.getDefaultInstance(),
            new StringReader(credentialsJson)
        );
    }
}
```

**Benefits:**
- No credentials in code or filesystem
- Automatic rotation support
- Audit logging of access
- Version management
- IAM-based access control

**Free Tier:** 10,000 access operations/month

#### 2. Cloud Logging (MEDIUM PRIORITY)

**Current Problem:**
```java
Logger logger = Logger.getLogger(APIConnection.class.getName());
logger.log(Level.INFO, ">> loading credentials");
```
- Logs go to console only
- No structured logging
- No log aggregation
- Hard to debug production issues

**GCP Solution:**
```gradle
implementation 'com.google.cloud:google-cloud-logging-logback:0.131.9-alpha'
```

```xml
<!-- logback.xml -->
<configuration>
    <appender name="CLOUD" class="com.google.cloud.logging.logback.LoggingAppender">
        <log>gmail-tui-app</log>
        <resourceType>global</resourceType>
    </appender>

    <root level="info">
        <appender-ref ref="CLOUD" />
    </root>
</configuration>
```

```java
// Modern logging
logger.info("Loading messages",
    kv("user", userId),
    kv("page", pageNum),
    kv("count", messageCount));
```

**Benefits:**
- Centralized logs in GCP Console
- Structured JSON logging
- Search and filter logs
- Integration with Error Reporting
- Retention policies

**Free Tier:** 50 GB ingestion/month

#### 3. Firestore / Cloud SQL (LOW PRIORITY - Phase 2)

**Use Case:** Local message cache persistence, user preferences

**Option A: Firestore (NoSQL)**
```gradle
implementation 'com.google.cloud:google-cloud-firestore:3.25.2'
```

```java
@Repository
public class FirestoreMessageRepository implements MessageRepository {
    private final Firestore firestore;

    public void saveMessage(Mail mail) {
        firestore.collection("users")
            .document(userId)
            .collection("messages")
            .document(mail.id())
            .set(mail);
    }

    public List<Mail> getCachedMessages(String userId, int limit) {
        return firestore.collection("users")
            .document(userId)
            .collection("messages")
            .orderBy("date", Direction.DESCENDING)
            .limit(limit)
            .get()
            .get()
            .getDocuments()
            .stream()
            .map(doc -> doc.toObject(Mail.class))
            .toList();
    }
}
```

**Benefits:**
- Serverless, auto-scaling
- Real-time sync (could sync across devices!)
- Offline support
- Generous free tier

**Free Tier:** 1 GB storage, 50K reads/day, 20K writes/day

**Option B: Cloud SQL (PostgreSQL)**
- Better for complex queries
- ACID guarantees
- More familiar to SQL developers
- Costs more than Firestore

**Recommendation:** Start without persistence (in-memory cache), add Firestore later if needed

#### 4. Cloud Storage (LOW PRIORITY)

**Use Case:** Cache message attachments locally

```gradle
implementation 'com.google.cloud:google-cloud-storage:2.43.2'
```

```java
@Service
public class AttachmentService {
    private final Storage storage;

    public void cacheAttachment(String messageId, String attachmentId, byte[] data) {
        BlobId blobId = BlobId.of("gmail-tui-attachments",
            userId + "/" + messageId + "/" + attachmentId);
        BlobInfo blobInfo = BlobInfo.newBuilder(blobId).build();
        storage.create(blobInfo, data);
    }
}
```

**Free Tier:** 5 GB storage

#### 5. Cloud Monitoring (LOW PRIORITY)

**Use Case:** Track application metrics

```gradle
implementation 'com.google.cloud:google-cloud-monitoring:3.52.0'
```

```java
// Track custom metrics
metricWriter.write(TimeSeries.newBuilder()
    .setMetric(Metric.newBuilder()
        .setType("custom.googleapis.com/gmail/messages_loaded")
        .build())
    .addPoints(Point.newBuilder()
        .setValue(TypedValue.newBuilder().setInt64Value(messageCount))
        .build())
    .build());
```

**Metrics to track:**
- Messages loaded per session
- API call duration
- Cache hit rate
- Error rate

### GCP Setup Guide

**1. Create GCP Project**
```bash
gcloud projects create gmail-tui-app
gcloud config set project gmail-tui-app
```

**2. Enable APIs**
```bash
gcloud services enable gmail.googleapis.com
gcloud services enable secretmanager.googleapis.com
gcloud services enable logging.googleapis.com
gcloud services enable firestore.googleapis.com
```

**3. Set up Application Default Credentials**
```bash
gcloud auth application-default login
```

**4. Create Secret for OAuth Credentials**
```bash
gcloud secrets create gmail-credentials --data-file=credentials.json
```

**5. Grant Access**
```bash
gcloud secrets add-iam-policy-binding gmail-credentials \
    --member="user:your-email@gmail.com" \
    --role="roles/secretmanager.secretAccessor"
```

### Cost Estimation

**Free Tier Usage (typical for personal use):**
- Gmail API: 1 billion quota units/day (FREE)
- Secret Manager: <10K accesses/month (FREE)
- Cloud Logging: <50 GB/month (FREE)
- Firestore: <1 GB storage (FREE)

**Estimated monthly cost: $0.00** (within free tier for personal use)

**If exceeding free tier:**
- Gmail API: Still free (no charges)
- Secret Manager: $0.06 per 10K accesses (beyond free tier)
- Cloud Logging: $0.50/GB (beyond 50GB)
- Firestore: $0.18/GB storage, $0.06 per 100K reads

**Realistic cost for personal use: $0-2/month**

---

## Implementation Phases

### Phase 0: Foundation & Critical Fixes (Week 1)
**Goal:** Fix critical bugs, modernize build system

**Tasks:**
1. **Fix Critical Bugs** (Day 1)
   - [ ] Fix header filtering logic (APIConnection.java:166)
   - [ ] Fix data overwrite bug (APIConnection.java:213-214)
   - [ ] Fix resource leak with try-with-resources
   - [ ] Fix unsafe null handling in getPayloadParts

2. **Modernize Build & Dependencies** (Day 2-3)
   - [ ] Upgrade to Java 21 (change sourceCompatibility/targetCompatibility)
   - [ ] Update all dependencies to latest versions
   - [ ] Remove unused dependencies (email-attachments, javax.mail)
   - [ ] Add SLF4J + Logback
   - [ ] Add Caffeine for caching
   - [ ] Add Lanterna for TUI
   - [ ] Fix test dependencies scope (testImplementation)

3. **Add Proper Encapsulation** (Day 4)
   - [ ] Convert Mail to record
   - [ ] Convert Headers to record
   - [ ] Convert Body to record
   - [ ] Add validation in compact constructors

4. **Testing Infrastructure** (Day 5)
   - [ ] Add Mockito
   - [ ] Add AssertJ
   - [ ] Create test fixtures for common test data
   - [ ] Mock Gmail API for tests

**Deliverables:**
- All critical bugs fixed
- Modern Java 21 build
- Up-to-date dependencies
- Proper data models with encapsulation
- Basic test infrastructure

**Testing:**
```bash
./gradlew clean build
./gradlew test
```

---

### Phase 1: Architecture Refactoring (Week 2-3)
**Goal:** Implement layered architecture, dependency injection

**Tasks:**
1. **Package Restructure** (Day 1-2)
   - [ ] Create new package structure (domain, service, repository, client, ui)
   - [ ] Move existing classes to appropriate packages
   - [ ] Update imports and references

2. **Implement Repository Layer** (Day 3-4)
   - [ ] Create GmailRepository interface
   - [ ] Implement GmailRepositoryImpl
   - [ ] Add in-memory caching with Caffeine
   - [ ] Implement cache eviction policies (1 hour TTL)

3. **Implement Service Layer** (Day 5-7)
   - [ ] Create GmailService interface
   - [ ] Implement GmailServiceImpl
   - [ ] Move business logic from APIConnection to service
   - [ ] Implement SearchService for email search
   - [ ] Create AuthenticationService for OAuth

4. **Dependency Injection** (Day 8-9)
   - [ ] Choose DI framework (Guice recommended for lightweight)
   - [ ] Create Guice modules
   - [ ] Refactor to constructor injection
   - [ ] Remove static dependencies

5. **Error Handling** (Day 10-11)
   - [ ] Create Result sealed interface
   - [ ] Create custom exception hierarchy
   - [ ] Implement retry logic with exponential backoff
   - [ ] Add rate limiting support

6. **Configuration Management** (Day 12-13)
   - [ ] Create AppConfig class
   - [ ] Externalize configuration (application.conf)
   - [ ] Support environment variables
   - [ ] Configuration validation

7. **Testing** (Day 14)
   - [ ] Unit tests for all services
   - [ ] Integration tests with mocked Gmail API
   - [ ] Repository tests with cache verification
   - [ ] Test coverage > 70%

**Deliverables:**
- Clean layered architecture
- Dependency injection throughout
- Comprehensive error handling
- Externalized configuration
- High test coverage

**Testing:**
```bash
./gradlew test
./gradlew integrationTest
./gradlew jacocoTestReport  # Check coverage
```

---

### Phase 2: Virtual Threads & Performance (Week 4)
**Goal:** Implement concurrent message loading with Java 21 virtual threads

**Tasks:**
1. **Virtual Thread Executor** (Day 1-2)
   - [ ] Create VirtualThreadExecutor configuration
   - [ ] Refactor message loading to use virtual threads
   - [ ] Implement progress callbacks for UI updates
   - [ ] Add cancellation support

2. **Batch Operations** (Day 3-4)
   - [ ] Implement batch message fetching
   - [ ] Batch header extraction
   - [ ] Optimize API call patterns
   - [ ] Reduce API quota usage

3. **Performance Testing** (Day 5)
   - [ ] Benchmark message loading (before/after)
   - [ ] Measure cache hit rates
   - [ ] Profile memory usage
   - [ ] Optimize bottlenecks

**Deliverables:**
- 10-15x faster message loading (40s → 2-3s for 100 emails)
- Progress indicators during loading
- Optimized API usage
- Performance benchmarks documented

**Testing:**
```bash
./gradlew :benchmark:run
# Target: <5 seconds for 100 messages
```

**Expected Performance:**
- Sequential (current): ~40 seconds for 100 messages
- Virtual threads: ~2-3 seconds for 100 messages
- Cache hit: <100ms for cached messages

---

### Phase 3: TUI Implementation (Week 5-6)
**Goal:** Build functional TUI with inbox browsing

**Tasks:**
1. **TUI Foundation** (Day 1-3)
   - [ ] Set up Lanterna
   - [ ] Create TuiApplication main class
   - [ ] Implement basic window management
   - [ ] Set up event handling
   - [ ] Create color scheme
   - [ ] Define key bindings

2. **Inbox View** (Day 4-7)
   - [ ] Create InboxView component
   - [ ] Implement email list rendering
   - [ ] Add pagination support
   - [ ] Implement keyboard navigation (↑↓, j/k)
   - [ ] Add email selection highlighting
   - [ ] Show unread indicators
   - [ ] Format dates nicely
   - [ ] Truncate long subjects/senders

3. **Email Detail View** (Day 8-10)
   - [ ] Create DetailView component
   - [ ] Render email headers (From, To, Date, Subject)
   - [ ] Display email body (plain text)
   - [ ] Handle HTML emails (convert to plain text)
   - [ ] Show labels
   - [ ] List attachments (view only, no download yet)
   - [ ] Back navigation (ESC)

4. **Status Bar & Help** (Day 11-12)
   - [ ] Create StatusBar component
   - [ ] Show current page/total pages
   - [ ] Display key shortcuts
   - [ ] Create Help screen (?)
   - [ ] Show loading indicators

5. **Polish** (Day 13-14)
   - [ ] Error message dialogs
   - [ ] Loading spinners
   - [ ] Smooth scrolling
   - [ ] Resize handling
   - [ ] Color customization

**Deliverables:**
- Fully functional TUI
- Navigate inbox with keyboard
- View email details
- Smooth user experience
- Help documentation in app

**Testing:**
```bash
./gradlew run
# Manual testing checklist:
# - Navigate inbox with arrow keys and j/k
# - Select email with Enter
# - View email details
# - Navigate back with ESC
# - Open help with ?
# - Quit with q
```

---

### Phase 4: Search & Filtering (Week 7)
**Goal:** Implement Gmail search functionality

**Tasks:**
1. **Search Backend** (Day 1-3)
   - [ ] Implement Gmail search query builder
   - [ ] Support common operators (from:, to:, subject:, has:attachment)
   - [ ] Implement date range searches
   - [ ] Cache search results
   - [ ] Handle search errors

2. **Search UI** (Day 4-5)
   - [ ] Create SearchView
   - [ ] Search input box
   - [ ] Display search operators help
   - [ ] Show search results in list
   - [ ] Navigate from search results to detail view

3. **Filtering** (Day 6-7)
   - [ ] Add label filter dropdown
   - [ ] Unread only toggle
   - [ ] Starred only filter
   - [ ] Combine filters with search

**Deliverables:**
- Full Gmail search support
- Filter by labels, read status
- Search UI integrated into TUI

**Testing:**
```bash
./gradlew test -Dtests=SearchServiceTest
# Manual testing:
# - Press / to open search
# - Try: from:boss@company.com
# - Try: has:attachment
# - Try: is:unread
```

---

### Phase 5: GCP Integration (Week 8) - OPTIONAL
**Goal:** Integrate GCP services for production-ready deployment

**Tasks:**
1. **Secret Manager** (Day 1-2)
   - [ ] Set up GCP project
   - [ ] Enable Secret Manager API
   - [ ] Migrate credentials.json to Secret Manager
   - [ ] Update CredentialManager to use GCP
   - [ ] Test OAuth flow with GCP secrets

2. **Cloud Logging** (Day 3-4)
   - [ ] Add Cloud Logging dependency
   - [ ] Configure Logback for GCP
   - [ ] Structured logging throughout app
   - [ ] Test log viewing in GCP Console

3. **Optional: Firestore** (Day 5-7)
   - [ ] Set up Firestore
   - [ ] Implement FirestoreMessageRepository
   - [ ] Persist message cache to Firestore
   - [ ] Sync read/starred status
   - [ ] Test offline cache loading

**Deliverables:**
- Production-ready credential management
- Centralized logging in GCP
- Optional: Persistent cache in Firestore

**Testing:**
```bash
gcloud auth application-default login
./gradlew run
# Verify in GCP Console:
# - Secret accessed in audit logs
# - Logs appearing in Cloud Logging
```

---

### Phase 6: Advanced Features (Week 9-10) - FUTURE
**Goal:** Additional functionality for power users

**Potential Features:**
1. **Email Actions**
   - [ ] Mark as read/unread (Space key)
   - [ ] Star/unstar (s key)
   - [ ] Archive email (e key)
   - [ ] Delete email (# key with confirmation)
   - [ ] Apply/remove labels

2. **Attachment Handling**
   - [ ] Download attachments (d key)
   - [ ] Save to specific directory
   - [ ] Preview text attachments
   - [ ] Show attachment icons by type

3. **Composition** (Advanced)
   - [ ] Reply to email (r key)
   - [ ] Reply all (a key)
   - [ ] Forward email (f key)
   - [ ] Compose new email (c key)
   - [ ] Draft management

4. **Multi-account Support**
   - [ ] Switch between Gmail accounts
   - [ ] Unified inbox view
   - [ ] Per-account credentials

5. **Offline Mode**
   - [ ] Sync messages to local storage
   - [ ] Queue actions for when online
   - [ ] Offline indicator

6. **Customization**
   - [ ] Custom key bindings
   - [ ] Color themes
   - [ ] Layout preferences
   - [ ] Config file (~/.gmail-tui/config)

---

## Success Metrics

### Phase 0-1 Success Criteria
- [x] All critical bugs fixed
- [x] Test coverage > 70%
- [x] Zero compile warnings
- [x] Clean architecture with proper separation of concerns
- [x] All dependencies up-to-date

### Phase 2-3 Success Criteria
- [x] Message loading < 5 seconds for 100 emails
- [x] Cache hit rate > 80% for repeated views
- [x] TUI loads in < 2 seconds
- [x] Smooth keyboard navigation (no lag)
- [x] Can browse entire inbox without crashes

### Phase 4-6 Success Criteria
- [x] Search results in < 3 seconds
- [x] All Gmail search operators supported
- [x] GCP integration working (if implemented)
- [x] User documentation complete
- [x] Production-ready error handling

---

## Documentation Needs

**Code Documentation:**
- [ ] Javadoc for all public APIs
- [ ] Architecture decision records (ADRs)
- [ ] Inline comments for complex logic

**User Documentation:**
- [ ] README.md with setup instructions
- [ ] QUICKSTART.md for first-time users
- [ ] Key bindings reference
- [ ] Gmail search syntax guide
- [ ] Troubleshooting guide

**Developer Documentation:**
- [ ] CONTRIBUTING.md
- [ ] Build and test instructions
- [ ] Architecture overview
- [ ] GCP setup guide
- [ ] Release process

---

## Risk Assessment

### High Risk
1. **Gmail API Rate Limits**
   - Mitigation: Implement caching, batch operations, rate limiting

2. **OAuth Token Expiration**
   - Mitigation: Automatic token refresh, user notification

3. **Terminal Compatibility**
   - Mitigation: Test on Windows/Mac/Linux, use Lanterna (cross-platform)

### Medium Risk
1. **Large Inbox Performance**
   - Mitigation: Pagination, virtual threads, lazy loading

2. **HTML Email Rendering**
   - Mitigation: Convert to plain text, show "View in browser" link

3. **Attachment Downloads**
   - Mitigation: Stream large files, show progress, size limits

### Low Risk
1. **Java Version Adoption**
   - Mitigation: Java 21 is stable LTS, well-tested

2. **GCP Costs**
   - Mitigation: Free tier sufficient, set billing alerts

---

## Next Steps

**Immediate Actions:**
1. Review this roadmap
2. Decide on must-have vs. nice-to-have features
3. Choose DI framework (Guice recommended)
4. Decide if GCP integration is desired
5. Set up development environment (Java 21, GCP account if needed)

**Week 1 Checklist:**
- [ ] Fix all 4 critical bugs
- [ ] Upgrade to Java 21
- [ ] Update all dependencies
- [ ] Convert data models to records
- [ ] Run tests and verify everything works

**Questions to Answer:**
1. Do you want GCP integration or keep it simple/local-only?
2. Should we use Guice (lightweight) or Spring Boot (full-featured)?
3. What's the priority: basic TUI ASAP vs. perfect architecture?
4. Do you need email composition or just read-only?
5. Single account or multi-account support?

---

## Conclusion

This roadmap transforms your Gmail API interface from a proof-of-concept into a production-ready TUI application. The phased approach ensures:

1. **Solid foundation** - Fix bugs, modernize stack (Weeks 1-3)
2. **Performance** - Virtual threads deliver 10x speedup (Week 4)
3. **Usability** - TUI makes it actually usable (Weeks 5-6)
4. **Features** - Search and filtering (Week 7)
5. **Production** - GCP integration optional (Week 8)
6. **Future** - Advanced features as needed (Weeks 9-10+)

**Estimated timeline:** 6-8 weeks for core functionality (Phases 0-4)
**Estimated effort:** 120-160 hours total

The result will be a modern, fast, maintainable Gmail TUI client that showcases Java 21 best practices and provides a great user experience.
