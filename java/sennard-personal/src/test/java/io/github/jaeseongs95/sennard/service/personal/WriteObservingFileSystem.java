// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.personal;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AccessMode;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.WatchService;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.nio.file.spi.FileSystemProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The default file system with one addition: at the moment bytes are about to reach a file, it records what that file looked like on disk
 * (its size and whether only its owner may open it). Nothing else changes, so production code runs against real files and the same test
 * compiles against the fixed code and against the code before the fix. An observation is taken at the instant of the write and not by
 * polling the directory, so the test has no timing of its own.
 */
final class WriteObservingFileSystem {
    record Observation(String file, long sizeBefore, boolean ownerOnly, String access) {
    }

    private final FileSystem real;
    private final Wrapper wrapper = new Wrapper();
    private final Provider provider = new Provider();
    private final List<Observation> observations = new CopyOnWriteArrayList<>();

    WriteObservingFileSystem(FileSystem real) {
        this.real = real;
    }

    List<Observation> observations() {
        return List.copyOf(observations);
    }

    Path wrap(Path path) {
        return path == null ? null : (Path) Proxy.newProxyInstance(Path.class.getClassLoader(), new Class<?>[] {Path.class}, new PathHandler(path));
    }

    private static Path unwrap(Path path) {
        if (path != null && Proxy.isProxyClass(path.getClass()) && Proxy.getInvocationHandler(path) instanceof PathHandler handler) {
            return handler.real;
        }
        return path;
    }

    /** Whether exactly the file's owner, and nobody else, has an allow entry on it. */
    static boolean ownerOnly(Path file) throws IOException {
        PosixFileAttributeView posix = Files.getFileAttributeView(file, PosixFileAttributeView.class);
        if (posix != null) {
            return posix.readAttributes().permissions().equals(PosixFilePermissions.fromString("rw-------"));
        }
        // The access list names the account of the SID in the process's token (a file's owner can be a group when the process is elevated).
        UserPrincipal owner = io.github.jaeseongs95.sennard.service.secret.ProcessUser.current().principal(unwrap(file).getParent(), java.nio.file.FileSystems.getDefault().getUserPrincipalLookupService());
        List<AclEntry> entries = Files.getFileAttributeView(file, AclFileAttributeView.class).getAcl();
        return entries.size() == 1 && entries.get(0).type() == AclEntryType.ALLOW && entries.get(0).principal().equals(owner);
    }

    static String describeAccess(Path file) throws IOException {
        PosixFileAttributeView posix = Files.getFileAttributeView(file, PosixFileAttributeView.class);
        if (posix != null) {
            return PosixFilePermissions.toString(posix.readAttributes().permissions());
        }
        List<String> principals = new ArrayList<>();
        for (AclEntry entry : Files.getFileAttributeView(file, AclFileAttributeView.class).getAcl()) {
            principals.add(entry.type() + ":" + entry.principal().getName());
        }
        return String.join(",", principals);
    }

    private Observation observe(Path file) throws IOException {
        return new Observation(file.getFileName().toString(), Files.size(file), ownerOnly(file), describeAccess(file));
    }

    private final class PathHandler implements InvocationHandler {
        final Path real;

        PathHandler(Path real) {
            this.real = real;
        }

        @Override
        public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] arguments) throws Throwable {
            if (method.getName().equals("getFileSystem") && method.getParameterCount() == 0) {
                return wrapper;
            }
            if (method.getName().equals("equals") && method.getParameterCount() == 1) {
                return arguments[0] instanceof Path other && real.equals(unwrap(other));
            }
            Object[] plain = arguments == null ? null : arguments.clone();
            if (plain != null) {
                for (int i = 0; i < plain.length; i++) {
                    if (plain[i] instanceof Path path) {
                        plain[i] = unwrap(path);
                    }
                }
            }
            try {
                Object result = method.invoke(real, plain);
                return result instanceof Path path ? wrap(path) : result;
            } catch (InvocationTargetException failure) {
                throw failure.getCause();
            }
        }
    }

    private final class Wrapper extends FileSystem {
        @Override
        public FileSystemProvider provider() {
            return provider;
        }

        @Override
        public void close() throws IOException {
            real.close();
        }

        @Override
        public boolean isOpen() {
            return real.isOpen();
        }

        @Override
        public boolean isReadOnly() {
            return real.isReadOnly();
        }

        @Override
        public String getSeparator() {
            return real.getSeparator();
        }

        @Override
        public Iterable<Path> getRootDirectories() {
            return real.getRootDirectories();
        }

        @Override
        public Iterable<FileStore> getFileStores() {
            return real.getFileStores();
        }

        @Override
        public Set<String> supportedFileAttributeViews() {
            return real.supportedFileAttributeViews();
        }

        @Override
        public Path getPath(String first, String... more) {
            return wrap(real.getPath(first, more));
        }

        @Override
        public PathMatcher getPathMatcher(String syntaxAndPattern) {
            return real.getPathMatcher(syntaxAndPattern);
        }

        @Override
        public UserPrincipalLookupService getUserPrincipalLookupService() {
            return real.getUserPrincipalLookupService();
        }

        @Override
        public WatchService newWatchService() throws IOException {
            return real.newWatchService();
        }
    }

    private final class Provider extends FileSystemProvider {
        private FileSystemProvider delegate() {
            return real.provider();
        }

        @Override
        public String getScheme() {
            return delegate().getScheme();
        }

        @Override
        public FileSystem newFileSystem(URI uri, Map<String, ?> environment) {
            throw new UnsupportedOperationException();
        }

        @Override
        public FileSystem getFileSystem(URI uri) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Path getPath(URI uri) {
            return wrap(delegate().getPath(uri));
        }

        @Override
        public SeekableByteChannel newByteChannel(Path path, Set<? extends OpenOption> options, FileAttribute<?>... attributes) throws IOException {
            Path file = unwrap(path);
            SeekableByteChannel channel = delegate().newByteChannel(file, options, attributes);
            return new SeekableByteChannel() {
                @Override
                public int read(ByteBuffer destination) throws IOException {
                    return channel.read(destination);
                }

                @Override
                public int write(ByteBuffer source) throws IOException {
                    if (source.hasRemaining()) {
                        observations.add(observe(file));
                    }
                    return channel.write(source);
                }

                @Override
                public long position() throws IOException {
                    return channel.position();
                }

                @Override
                public SeekableByteChannel position(long newPosition) throws IOException {
                    channel.position(newPosition);
                    return this;
                }

                @Override
                public long size() throws IOException {
                    return channel.size();
                }

                @Override
                public SeekableByteChannel truncate(long size) throws IOException {
                    channel.truncate(size);
                    return this;
                }

                @Override
                public boolean isOpen() {
                    return channel.isOpen();
                }

                @Override
                public void close() throws IOException {
                    channel.close();
                }
            };
        }

        @Override
        public FileChannel newFileChannel(Path path, Set<? extends OpenOption> options, FileAttribute<?>... attributes) {
            throw new UnsupportedOperationException("The sealer writes through newByteChannel; a file channel would escape the observation.");
        }

        @Override
        public DirectoryStream<Path> newDirectoryStream(Path directory, DirectoryStream.Filter<? super Path> filter) throws IOException {
            DirectoryStream<Path> stream = delegate().newDirectoryStream(unwrap(directory), entry -> filter.accept(wrap(entry)));
            return new DirectoryStream<>() {
                @Override
                public java.util.Iterator<Path> iterator() {
                    java.util.Iterator<Path> inner = stream.iterator();
                    return new java.util.Iterator<>() {
                        @Override
                        public boolean hasNext() {
                            return inner.hasNext();
                        }

                        @Override
                        public Path next() {
                            return wrap(inner.next());
                        }
                    };
                }

                @Override
                public void close() throws IOException {
                    stream.close();
                }
            };
        }

        @Override
        public void createDirectory(Path directory, FileAttribute<?>... attributes) throws IOException {
            delegate().createDirectory(unwrap(directory), attributes);
        }

        @Override
        public void delete(Path path) throws IOException {
            delegate().delete(unwrap(path));
        }

        @Override
        public void copy(Path source, Path target, CopyOption... options) throws IOException {
            delegate().copy(unwrap(source), unwrap(target), options);
        }

        @Override
        public void move(Path source, Path target, CopyOption... options) throws IOException {
            delegate().move(unwrap(source), unwrap(target), options);
        }

        @Override
        public void createLink(Path link, Path existing) throws IOException {
            delegate().createLink(unwrap(link), unwrap(existing));
        }

        @Override
        public boolean isSameFile(Path path, Path other) throws IOException {
            return delegate().isSameFile(unwrap(path), unwrap(other));
        }

        @Override
        public boolean isHidden(Path path) throws IOException {
            return delegate().isHidden(unwrap(path));
        }

        @Override
        public FileStore getFileStore(Path path) throws IOException {
            return delegate().getFileStore(unwrap(path));
        }

        @Override
        public void checkAccess(Path path, AccessMode... modes) throws IOException {
            delegate().checkAccess(unwrap(path), modes);
        }

        @Override
        public <V extends FileAttributeView> V getFileAttributeView(Path path, Class<V> type, LinkOption... options) {
            return delegate().getFileAttributeView(unwrap(path), type, options);
        }

        @Override
        public <A extends BasicFileAttributes> A readAttributes(Path path, Class<A> type, LinkOption... options) throws IOException {
            return delegate().readAttributes(unwrap(path), type, options);
        }

        @Override
        public Map<String, Object> readAttributes(Path path, String attributes, LinkOption... options) throws IOException {
            return delegate().readAttributes(unwrap(path), attributes, options);
        }

        @Override
        public void setAttribute(Path path, String attribute, Object value, LinkOption... options) throws IOException {
            delegate().setAttribute(unwrap(path), attribute, value, options);
        }
    }
}
