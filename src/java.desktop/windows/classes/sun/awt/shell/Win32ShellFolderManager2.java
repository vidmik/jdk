/*
 * Copyright (c) 2003, 2025, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package sun.awt.shell;

import java.awt.Image;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import sun.awt.util.ThreadGroupUtils;
import sun.util.logging.PlatformLogger;

import static sun.awt.shell.Win32ShellFolder2.DESKTOP;
import static sun.awt.shell.Win32ShellFolder2.DRIVES;
import static sun.awt.shell.Win32ShellFolder2.Invoker;
import static sun.awt.shell.Win32ShellFolder2.LARGE_ICON_SIZE;
import static sun.awt.shell.Win32ShellFolder2.MultiResolutionIconImage;
import static sun.awt.shell.Win32ShellFolder2.NETWORK;
import static sun.awt.shell.Win32ShellFolder2.PERSONAL;
import static sun.awt.shell.Win32ShellFolder2.RECENT;
import static sun.awt.shell.Win32ShellFolder2.SMALL_ICON_SIZE;
// NOTE: This class supersedes Win32ShellFolderManager, which was removed
//       from distribution after version 1.4.2.

/**
 * @author Michael Martak
 * @author Leif Samuelsson
 * @author Kenneth Russell
 * @since 1.4
 */

final class Win32ShellFolderManager2 extends ShellFolderManager {

    private static final PlatformLogger
            log = PlatformLogger.getLogger("sun.awt.shell.Win32ShellFolderManager2");

    static {
        // Load library here
        sun.awt.windows.WToolkit.loadLibraries();
    }

    @Override
    public ShellFolder createShellFolder(File file) throws FileNotFoundException {
        try {
            return createShellFolder(getDesktop(), file);
        } catch (InterruptedException e) {
            throw new FileNotFoundException("Execution was interrupted");
        }
    }

    static Win32ShellFolder2 createShellFolder(Win32ShellFolder2 parent, File file)
            throws FileNotFoundException, InterruptedException {
        long pIDL;
        try {
            pIDL = parent.parseDisplayName(file.getCanonicalPath());
        } catch (IOException ex) {
            pIDL = 0;
        }
        if (pIDL == 0) {
            // Shouldn't happen but watch for it anyway
            throw new FileNotFoundException("File " + file.getAbsolutePath() + " not found");
        }

        try {
            return createShellFolderFromRelativePIDL(parent, pIDL);
        } finally {
            Win32ShellFolder2.releasePIDL(pIDL);
        }
    }

    static Win32ShellFolder2 createShellFolderFromRelativePIDL(Win32ShellFolder2 parent, long pIDL)
            throws InterruptedException, FileNotFoundException
    {
        // Walk down this relative pIDL, creating new nodes for each of the entries
        while (pIDL != 0) {
            long curPIDL = Win32ShellFolder2.copyFirstPIDLEntry(pIDL);
            if (curPIDL != 0) {
                if (!parent.isDirectory()) {
                    throw new FileNotFoundException("not a directory");
                }
                parent = Win32ShellFolder2.createShellFolder(parent, curPIDL);
                pIDL = Win32ShellFolder2.getNextPIDLEntry(pIDL);
            } else {
                // The list is empty if the parent is Desktop and pIDL is a shortcut to Desktop
                break;
            }
        }
        return parent;
    }

    private static final int VIEW_LIST = 2;
    private static final int VIEW_DETAILS = 3;
    private static final int VIEW_PARENTFOLDER = 8;
    private static final int VIEW_NEWFOLDER = 11;

    private static final Image[] STANDARD_VIEW_BUTTONS = new Image[12];

    private static Image getStandardViewButton(int iconIndex) {
        Image result = STANDARD_VIEW_BUTTONS[iconIndex];

        if (result != null) {
            return result;
        }

        final int[] iconBits = Win32ShellFolder2
                .getStandardViewButton0(iconIndex, true);
        if (iconBits != null) {
            // icons are always square
            final int size = (int) Math.sqrt(iconBits.length);
            final BufferedImage img =
                    new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            img.setRGB(0, 0, size, size, iconBits, 0, size);

            STANDARD_VIEW_BUTTONS[iconIndex] = (size == SMALL_ICON_SIZE)
                    ? img
                    : new MultiResolutionIconImage(SMALL_ICON_SIZE, img);
        }

        return STANDARD_VIEW_BUTTONS[iconIndex];
    }

    // Special folders
    private static Win32ShellFolder2 desktop;
    private static Win32ShellFolder2 drives;
    private static Win32ShellFolder2 recent;
    private static Win32ShellFolder2 network;
    private static Win32ShellFolder2 personal;

    static Win32ShellFolder2 getDesktop() {
        if (desktop == null) {
            try {
                desktop = new Win32ShellFolder2(DESKTOP);
            } catch (IOException | InterruptedException e) {
                if (log.isLoggable(PlatformLogger.Level.WARNING)) {
                    log.warning("Cannot access 'Desktop'", e);
                }
            }
        }
        return desktop;
    }

    static Win32ShellFolder2 getDrives() {
        if (drives == null) {
            try {
                drives = new Win32ShellFolder2(DRIVES);
            } catch (IOException | InterruptedException e) {
                if (log.isLoggable(PlatformLogger.Level.WARNING)) {
                    log.warning("Cannot access 'Drives'", e);
                }
            }
        }
        return drives;
    }

    static Win32ShellFolder2 getRecent() {
        if (recent == null) {
            try {
                String path = Win32ShellFolder2.getFileSystemPath(RECENT);
                if (path != null) {
                    recent = createShellFolder(getDesktop(), new File(path));
                }
            } catch (InterruptedException | IOException e) {
                if (log.isLoggable(PlatformLogger.Level.WARNING)) {
                    log.warning("Cannot access 'Recent'", e);
                }
            }
        }
        return recent;
    }

    static Win32ShellFolder2 getNetwork() {
        if (network == null) {
            try {
                network = new Win32ShellFolder2(NETWORK);
            } catch (IOException | InterruptedException e) {
                if (log.isLoggable(PlatformLogger.Level.WARNING)) {
                    log.warning("Cannot access 'Network'", e);
                }
            }
        }
        return network;
    }

    static Win32ShellFolder2 getPersonal() {
        if (personal == null) {
            try {
                String path = Win32ShellFolder2.getFileSystemPath(PERSONAL);
                if (path != null) {
                    Win32ShellFolder2 desktop = getDesktop();
                    personal = desktop.getChildByPath(path);
                    if (personal == null) {
                        personal = createShellFolder(getDesktop(), new File(path));
                    }
                    if (personal != null) {
                        personal.setIsPersonal();
                    }
                }
            } catch (InterruptedException | IOException e) {
                if (log.isLoggable(PlatformLogger.Level.WARNING)) {
                    log.warning("Cannot access 'Personal'", e);
                }
            }
        }
        return personal;
    }


    private static File[] roots;

    /**
     * @param key a {@code String}
     *  "fileChooserDefaultFolder":
     *    Returns a {@code File} - the default shellfolder for a new filechooser
     *  "roots":
     *    Returns a {@code File[]} - containing the root(s) of the displayable hierarchy
     *  "fileChooserComboBoxFolders":
     *    Returns a {@code File[]} - an array of shellfolders representing the list to
     *    show by default in the file chooser's combobox
     *   "fileChooserShortcutPanelFolders":
     *    Returns a {@code File[]} - an array of shellfolders representing well-known
     *    folders, such as Desktop, Documents, History, Network, Home, etc.
     *    This is used in the shortcut panel of the filechooser on Windows 2000
     *    and Windows Me.
     *  "fileChooserIcon <icon>":
     *    Returns an {@code Image} - icon can be ListView, DetailsView, UpFolder, NewFolder or
     *    ViewMenu (Windows only).
     *  "optionPaneIcon iconName":
     *    Returns an {@code Image} - icon from the system icon list
     *
     * @return An Object matching the key string.
     */
    @Override
    public Object get(String key) {
        if (key.equals("fileChooserDefaultFolder")) {
            File file = getPersonal();
            if (file == null) {
                file = getDesktop();
            }
            return file;
        } else if (key.equals("roots")) {
            // Should be "History" and "Desktop" ?
            if (roots == null) {
                File desktop = getDesktop();
                if (desktop != null) {
                    roots = new File[] { desktop };
                } else {
                    roots = (File[])super.get(key);
                }
            }
            return roots;
        } else if (key.equals("fileChooserComboBoxFolders")) {
            Win32ShellFolder2 desktop = getDesktop();

            if (desktop != null) {
                ArrayList<File> folders = new ArrayList<File>();
                Win32ShellFolder2 drives = getDrives();

                Win32ShellFolder2 recentFolder = getRecent();
                if (recentFolder != null) {
                    folders.add(recentFolder);
                }

                folders.add(desktop);
                // Add all second level folders
                File[] secondLevelFolders = desktop.listFiles();
                Arrays.sort(secondLevelFolders);
                for (File secondLevelFolder : secondLevelFolders) {
                    Win32ShellFolder2 folder = (Win32ShellFolder2) secondLevelFolder;
                    if (!folder.isFileSystem() || (folder.isDirectory() && !folder.isLink())) {
                        folders.add(folder);
                        // Add third level for "My Computer"
                        if (folder.equals(drives)) {
                            File[] thirdLevelFolders = folder.listFiles();
                            if (thirdLevelFolders != null && thirdLevelFolders.length > 0) {
                                List<File> thirdLevelFoldersList = Arrays.asList(thirdLevelFolders);

                                folder.sortChildren(thirdLevelFoldersList);
                                folders.addAll(thirdLevelFoldersList);
                            }
                        }
                    }
                }
                return folders.toArray(new File[folders.size()]);
            } else {
                return super.get(key);
            }
        } else if (key.equals("fileChooserShortcutPanelFolders")) {
            Toolkit toolkit = Toolkit.getDefaultToolkit();
            ArrayList<File> folders = new ArrayList<File>();
            int i = 0;
            Object value;
            do {
                value = toolkit.getDesktopProperty("win.comdlg.placesBarPlace" + i++);
                try {
                    if (value instanceof Integer) {
                        // A CSIDL
                        folders.add(new Win32ShellFolder2((Integer)value));
                    } else if (value instanceof String) {
                        // A path
                        folders.add(createShellFolder(new File((String)value)));
                    }
                } catch (IOException e) {
                    if (log.isLoggable(PlatformLogger.Level.WARNING)) {
                        log.warning("Cannot read value = " + value, e);
                    }
                    // Skip this value
                } catch (InterruptedException e) {
                    if (log.isLoggable(PlatformLogger.Level.WARNING)) {
                        log.warning("Cannot read value = " + value, e);
                    }
                    // Return empty result
                    return new File[0];
                }
            } while (value != null);

            if (folders.size() == 0) {
                // Use default list of places
                for (File f : new File[] {
                    getRecent(), getDesktop(), getPersonal(), getDrives(), getNetwork()
                }) {
                    if (f != null) {
                        folders.add(f);
                    }
                }
            }
            return folders.toArray(new File[folders.size()]);
        } else if (key.startsWith("fileChooserIcon ")) {
            String name = key.substring(key.indexOf(" ") + 1);

            int iconIndex;

            if (name.equals("ListView") || name.equals("ViewMenu")) {
                iconIndex = VIEW_LIST;
            } else if (name.equals("DetailsView")) {
                iconIndex = VIEW_DETAILS;
            } else if (name.equals("UpFolder")) {
                iconIndex = VIEW_PARENTFOLDER;
            } else if (name.equals("NewFolder")) {
                iconIndex = VIEW_NEWFOLDER;
            } else {
                return null;
            }

            return getStandardViewButton(iconIndex);
        } else if (key.startsWith("optionPaneIcon ")) {
            Win32ShellFolder2.SystemIcon iconType;
            if (key == "optionPaneIcon Error") {
                iconType = Win32ShellFolder2.SystemIcon.IDI_ERROR;
            } else if (key == "optionPaneIcon Information") {
                iconType = Win32ShellFolder2.SystemIcon.IDI_INFORMATION;
            } else if (key == "optionPaneIcon Question") {
                iconType = Win32ShellFolder2.SystemIcon.IDI_QUESTION;
            } else if (key == "optionPaneIcon Warning") {
                iconType = Win32ShellFolder2.SystemIcon.IDI_EXCLAMATION;
            } else {
                return null;
            }
            return Win32ShellFolder2.getSystemIcon(iconType);
        } else if (key.startsWith("shell32Icon ") || key.startsWith("shell32LargeIcon ")) {
            String name = key.substring(key.indexOf(" ") + 1);
            try {
                int i = Integer.parseInt(name);
                if (i >= 0) {
                    return Win32ShellFolder2.getShell32Icon(i,
                         key.startsWith("shell32LargeIcon ") ? LARGE_ICON_SIZE : SMALL_ICON_SIZE);
                }
            } catch (NumberFormatException ex) {
            }
        }
        return null;
    }

    /**
     * Does {@code dir} represent a "computer" such as a node on the network, or
     * "My Computer" on the desktop.
     */
    @Override
    public boolean isComputerNode(final File dir) {
        if (dir != null && dir == getDrives()) {
            return true;
        } else {
            String path = dir.getAbsolutePath();

            return (path.startsWith("\\\\") && path.indexOf("\\", 2) < 0);      //Network path
        }
    }

    @Override
    public boolean isFileSystemRoot(File dir) {
        //Note: Removable drives don't "exist" but are listed in "My Computer"
        if (dir != null) {

            if (dir instanceof Win32ShellFolder2) {
                Win32ShellFolder2 sf = (Win32ShellFolder2)dir;

                //This includes all the drives under "My PC" or "My Computer.
                // On windows 10, "External Drives" are listed under "Desktop"
                // also
                return  (sf.isFileSystem() && sf.parent != null &&
                        (sf.parent.equals (getDrives()) ||
                        (sf.parent.equals (getDesktop()) && isDrive(dir))));
            }
            return isDrive(dir);
        }
        return false;
    }

    private boolean isDrive(File dir) {
        String path = dir.getPath();
        if (path.length() != 3 || path.charAt(1) != ':') {
            return false;
        }
        File[] roots = Win32ShellFolder2.listRoots();
        return roots != null && Arrays.asList(roots).contains(dir);
    }

    private static List<Win32ShellFolder2> topFolderList = null;
    static int compareShellFolders(Win32ShellFolder2 sf1, Win32ShellFolder2 sf2) {
        boolean special1 = sf1.isSpecial();
        boolean special2 = sf2.isSpecial();

        if (special1 && special2) {
            if (topFolderList == null) {
                ArrayList<Win32ShellFolder2> tmpTopFolderList = new ArrayList<>();
                tmpTopFolderList.add(Win32ShellFolderManager2.getPersonal());
                tmpTopFolderList.add(Win32ShellFolderManager2.getDesktop());
                tmpTopFolderList.add(Win32ShellFolderManager2.getDrives());
                tmpTopFolderList.add(Win32ShellFolderManager2.getNetwork());
                topFolderList = tmpTopFolderList;
            }
            int i1 = topFolderList.indexOf(sf1);
            int i2 = topFolderList.indexOf(sf2);
            if (i1 >= 0 && i2 >= 0) {
                return (i1 - i2);
            } else if (i1 >= 0) {
                return -1;
            } else if (i2 >= 0) {
                return 1;
            }
        }

        // Non-file shellfolders sort before files
        if (special1 && !special2) {
            return -1;
        } else if (special2 && !special1) {
            return  1;
        }

        return compareNames(sf1.getAbsolutePath(), sf2.getAbsolutePath());
    }

    static int compareNames(String name1, String name2) {
        // First ignore case when comparing
        int diff = name1.compareToIgnoreCase(name2);
        if (diff != 0) {
            return diff;
        } else {
            // May differ in case (e.g. "mail" vs. "Mail")
            // We need this test for consistent sorting
            return name1.compareTo(name2);
        }
    }

    @Override
    protected Invoker createInvoker() {
        return new ComInvoker();
    }

    private static final class ComInvoker extends ThreadPoolExecutor implements ThreadFactory, ShellFolder.Invoker {
        private static Thread comThread;

        private ComInvoker() {
            super(1, 1, 0, TimeUnit.DAYS, new LinkedBlockingQueue<>());
            allowCoreThreadTimeOut(false);
            setThreadFactory(this);
            final Runnable shutdownHook = () -> shutdownNow();
            Thread t = new Thread(
                    ThreadGroupUtils.getRootThreadGroup(), shutdownHook,
                    "ShellFolder", 0, false);
            Runtime.getRuntime().addShutdownHook(t);
        }

        @Override
        public synchronized Thread newThread(final Runnable task) {
            final Runnable comRun = new Runnable() {
                public void run() {
                    try {
                        initializeCom();
                        task.run();
                    } finally {
                        uninitializeCom();
                    }
                }
            };
            /* The thread must be a member of a thread group
             * which will not get GCed before VM exit.
             * Make its parent the top-level thread group.
             */
            comThread = new Thread(
                    ThreadGroupUtils.getRootThreadGroup(), comRun, "Swing-Shell",
                    0, false);
            comThread.setDaemon(true);
            /* This is important, since this thread running at lower priority
               leads to memory consumption when listDrives() function is called
               repeatedly.
             */
            comThread.setPriority(Thread.MAX_PRIORITY);
            return comThread;
        }

        @Override
        public <T> T invoke(Callable<T> task) throws Exception {
            if (Thread.currentThread() == comThread) {
                // if it's already called from the COM
                // thread, we don't need to delegate the task
                return task.call();
            } else {
                final Future<T> future;

                try {
                    future = submit(task);
                } catch (RejectedExecutionException e) {
                    throw new InterruptedException(e.getMessage());
                }

                try {
                    return future.get();
                } catch (InterruptedException e) {
                    future.cancel(true);


                    throw e;
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();

                    if (cause instanceof Exception) {
                        throw (Exception) cause;
                    }

                    if (cause instanceof Error) {
                        throw (Error) cause;
                    }

                    throw new RuntimeException("Unexpected error", cause);
                }
            }
        }
    }

    static native void initializeCom();

    static native void uninitializeCom();
}
