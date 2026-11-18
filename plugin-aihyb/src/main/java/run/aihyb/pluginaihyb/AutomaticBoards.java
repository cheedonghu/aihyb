package run.aihyb.pluginaihyb;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Component
public class AutomaticBoards {
    private static final Logger LOG = LoggerFactory.getLogger(AutomaticBoards.class);
    private static final Pattern NAME = Pattern.compile("[0-9]{20}-([0-9a-f]{64})\\.json");
    static final int MAX_BATCH_BYTES = 1024 * 1024;
    private static final LinkOption NO_LINKS = LinkOption.NOFOLLOW_LINKS;
    private final PackageCodec codec;
    private final Map<String, Store> stores;
    private final long refreshNanos;

    @Autowired
    public AutomaticBoards(PackageCodec codec, @Value("${aihyb.module-data-dir:${AIHYB_MODULE_DATA_DIR:}}") String directory) {
        this(configuredPath(directory), codec);
    }

    public AutomaticBoards(Path directory, PackageCodec codec) {
        this(directory, codec, java.time.Duration.ofSeconds(1));
    }

    AutomaticBoards(Path directory, PackageCodec codec, java.time.Duration refresh) {
        this.codec = codec;
        this.refreshNanos = refresh.toNanos();
        stores = directory == null ? Map.of() : Map.of(
            AutomaticBoardBatch.OPEN, new Store(directory, AutomaticBoardBatch.OPEN),
            AutomaticBoardBatch.DIAL, new Store(directory, AutomaticBoardBatch.DIAL));
    }

    private static Path configuredPath(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Path.of(value);
        } catch (InvalidPathException error) {
            LOG.warn("Invalid automatic board directory setting");
            return null;
        }
    }

    public static boolean owns(String id) {
        return AutomaticBoardBatch.adapterFor(id) != null;
    }

    public Mono<List<PackageViews.PublishedContent>> published() {
        if (stores.isEmpty()) return Mono.just(List.of());
        return Mono.fromCallable(() -> {
            var published = new ArrayList<PackageViews.PublishedContent>();
            for (var store : stores.values()) {
                var batch = store.latest();
                if (batch != null) batch.boards().keySet().forEach(id -> published.add(batch.view(id, codec)));
            }
            published.sort(Comparator.comparing(PackageViews.PublishedContent::publicationTime).reversed()
                .thenComparing(PackageViews.PublishedContent::content_id));
            return List.copyOf(published);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<PackageViews.PublishedContent> byId(String id) {
        return current(id).map(batch -> batch.view(id, codec));
    }

    public Mono<JsonNode> payload(String id) {
        return current(id).map(batch -> batch.boards().get(id).deepCopy());
    }

    private Mono<AutomaticBoardBatch> current(String id) {
        var adapter = AutomaticBoardBatch.adapterFor(id);
        var store = adapter == null ? null : stores.get(adapter);
        return store == null ? Mono.empty() : Mono.fromCallable(store::latest).subscribeOn(Schedulers.boundedElastic());
    }

    // Only one successful body per adapter stays in memory. Failed immutable files need only a
    // metadata stamp. A truncated/replaced file is reconsidered even when its name is unchanged.
    private record Stamp(Path path, Object fileKey, long size, FileTime modified) {}

    private final class Store {
        private final String adapter;
        private final Path directory;
        private final Map<Path, Stamp> rejected = new HashMap<>();
        private Stamp accepted;
        private AutomaticBoardBatch batch;

        Store(Path root, String adapter) {
            this.adapter = adapter;
            this.directory = root.toAbsolutePath().normalize().resolve(adapter);
        }

        private record Cached(long until, AutomaticBoardBatch batch) {}
        private volatile Cached cached;

        AutomaticBoardBatch latest() {
            var hit = cached;
            if (fresh(hit)) return hit.batch();
            synchronized (this) {
                if (fresh(cached)) return cached.batch();
                var result = scan();
                cached = new Cached(System.nanoTime() + refreshNanos, result);
                return result;
            }
        }

        private boolean fresh(Cached value) {
            return value != null && value.until() - System.nanoTime() > 0;
        }

        private AutomaticBoardBatch scan() {
            try {
                if (!directory.toRealPath().equals(directory)) {
                    LOG.warn("Ignoring linked automatic board directory {}", adapter);
                    return clear();
                }
                // The mount root is host-owned. Pin its adapter directory and open children
                // relative to that handle on providers that support openat (including Linux).
                try (var parent = Files.newDirectoryStream(directory.getParent());
                     var listing = parent instanceof SecureDirectoryStream<Path> secure
                         ? secure.newDirectoryStream(directory.getFileName(), NO_LINKS) : Files.newDirectoryStream(directory)) {
                    return select(listing);
                }
            } catch (NoSuchFileException absent) {
                return clear();
            } catch (IOException | RuntimeException error) {
                LOG.warn("Cannot scan automatic board directory {}: {}", adapter, error.toString());
                return batch;
            }
        }

        private AutomaticBoardBatch clear() {
            accepted = null;
            batch = null;
            return null;
        }

        private boolean unexpectedNames;

        private AutomaticBoardBatch select(DirectoryStream<Path> listing) {
            var candidates = new java.util.TreeMap<String, Path>(Comparator.reverseOrder());
            var duplicates = new java.util.HashSet<String>();
            boolean malformed = false;
            for (var path : listing) {
                var name = path.getFileName();
                var text = name.toString();
                if (NAME.matcher(text).matches()) {
                    var sequence = text.substring(0, 20);
                    if (candidates.put(sequence, name) != null) duplicates.add(sequence);
                } else if (text.endsWith(".json")) {
                    malformed = true;
                }
            }
            boolean ambiguous = !duplicates.isEmpty() || malformed;
            if (ambiguous && !unexpectedNames) LOG.warn("Ignoring malformed or duplicate automatic board filenames in {}", adapter);
            unexpectedNames = ambiguous;
            if (!rejected.isEmpty()) rejected.keySet().retainAll(Set.copyOf(candidates.values()));
            for (var entry : candidates.entrySet()) {
                if (duplicates.contains(entry.getKey())) continue;
                var selected = candidate(listing, entry.getValue());
                if (selected != null) return selected;
            }
            return clear();
        }

        private AutomaticBoardBatch candidate(DirectoryStream<Path> listing, Path name) {
            try {
                var secure = listing instanceof SecureDirectoryStream<Path> stream ? stream : null;
                var path = directory.resolve(name);
                var attrs = secure == null ? Files.readAttributes(path, BasicFileAttributes.class, NO_LINKS)
                    : secure.getFileAttributeView(name, BasicFileAttributeView.class, NO_LINKS).readAttributes();
                var stamp = new Stamp(name, attrs.fileKey(), attrs.size(), attrs.lastModifiedTime());
                if (stamp.equals(rejected.get(name))) return null;
                try {
                    if (!attrs.isRegularFile() || attrs.size() > MAX_BATCH_BYTES) {
                        throw new IllegalArgumentException("Expected a regular generation file within the size limit");
                    }
                    if (stamp.equals(accepted)) return batch;
                    if (secure == null && !directory.toRealPath().equals(directory)) throw new IOException("Linked board directory");
                    try (var channel = secure == null ? Files.newByteChannel(path, StandardOpenOption.READ, NO_LINKS)
                        : secure.newByteChannel(name, Set.of(StandardOpenOption.READ, NO_LINKS))) {
                        var bytes = java.nio.channels.Channels.newInputStream(channel).readNBytes(MAX_BATCH_BYTES + 1);
                        if (bytes.length != attrs.size()) throw new IllegalArgumentException("Generation length changed");
                        var expected = name.toString().substring(21, 85);
                        if (!expected.equals(sha256(bytes))) throw new IllegalArgumentException("Generation checksum mismatch");
                        batch = decode(expected, bytes);
                        accepted = stamp;
                        return batch;
                    }
                } catch (IllegalArgumentException | PackageProblem error) {
                    rejected.put(name, stamp);
                    LOG.warn("Ignoring invalid automatic board generation {}/{}: {}", adapter, name, error.getMessage());
                }
            } catch (NoSuchFileException removed) {
                return null;
            } catch (IOException | RuntimeException error) {
                LOG.warn("Cannot read automatic board generation {}/{}: {}", adapter, name, error.toString());
                // A transient filesystem failure on the selected file is not a withdrawal.
                if (accepted != null && accepted.path().equals(name)) return batch;
            }
            return null;
        }

        private AutomaticBoardBatch decode(String expected, byte[] bytes) {
            try {
                var json = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
                return AutomaticBoardBatch.parse(adapter, expected, json, codec);
            } catch (CharacterCodingException error) {
                throw new IllegalArgumentException("Invalid UTF-8 in generation");
            } catch (IOException error) {
                throw new IllegalArgumentException("Invalid JSON in generation");
            }
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}
