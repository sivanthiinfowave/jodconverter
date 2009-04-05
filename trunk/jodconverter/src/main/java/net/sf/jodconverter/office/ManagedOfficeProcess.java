//
// JODConverter - Java OpenDocument Converter
// Copyright (C) 2004-2009 - Mirko Nasato and Contributors
//
// JODConverter is free software: you can redistribute it and/or
// modify it under the terms of the GNU Lesser General Public License
// as published by the Free Software Foundation, either version 3 of
// the License, or (at your option) any later version.
//
// JODConverter is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
// Lesser General Public License for more details.
//
// You should have received a copy of the GNU Lesser General
// Public License along with JODConverter.  If not, see
// <http://www.gnu.org/licenses/>.
//
package net.sf.jodconverter.office;

import java.io.File;
import java.io.IOException;
import java.net.ConnectException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.logging.Logger;

import net.sf.jodconverter.util.NamedThreadFactory;
import net.sf.jodconverter.util.RetryTimeoutException;
import net.sf.jodconverter.util.Retryable;
import net.sf.jodconverter.util.TemporaryException;

import org.apache.commons.io.FileUtils;

import com.sun.star.frame.XDesktop;
import com.sun.star.lang.DisposedException;

class ManagedOfficeProcess {

    private static final ThreadFactory THREAD_FACTORY = new NamedThreadFactory("ManagedOfficeProcessThread");

    private final ManagedOfficeProcessConfiguration configuration;

    private final OfficeProcess process;
    private final OfficeConnection connection;
    private final File profileDir;

    private ExecutorService executor = Executors.newSingleThreadExecutor(THREAD_FACTORY);

    private final Logger logger = Logger.getLogger(getClass().getName());

    public ManagedOfficeProcess(ManagedOfficeProcessConfiguration configuration) throws OfficeException {
        // TODO validate configuration (here or in ManagedProcessOfficeManager?)
//        if (!officeHome.isDirectory()) {
//            throw new IllegalArgumentException("officeHome doesn't exist: " + officeHome);
//        }
//        if (templateProfileDir != null && !templateProfileDir.isDirectory()) {
//            throw new IllegalArgumentException("templateProfileDir doesn't exist: " + templateProfileDir);
//        }
        this.configuration = configuration;
        profileDir = getProfileDir(configuration.getConnectionMode());
        process = new OfficeProcess(configuration, profileDir);
        connection = new OfficeConnection(configuration.getConnectionMode());
    }

    private File getProfileDir(OfficeConnectionMode connectionMode) {
        String dirName = ".jodconverter_" + connectionMode.getAcceptString().replace(',', '_').replace('=', '-');
        return new File(System.getProperty("java.io.tmpdir"), dirName);
    }

    private void prepareProfileDir() throws OfficeException {
        if (profileDir.exists()) {
            logger.warning(String.format("profile dir '%s' already exists; deleting", profileDir));
            doDeleteProfileDir();
        }
        if (configuration.getTemplateProfileDir() != null) {
            try {
                FileUtils.copyDirectory(configuration.getTemplateProfileDir(), profileDir);
            } catch (IOException ioException) {
                throw new OfficeException("failed to create profileDir", ioException);
            }
        }
    }

    public OfficeConnection getConnection() {
        return connection;
    }

    public void startAndWait() throws OfficeException {
        Future<?> future = executor.submit(new Runnable() {
            public void run() {
                doStartProcessAndConnect();
            }
        });
        try {
            future.get();
        } catch (Exception exception) {
            throw new OfficeException("failed to start and connect", exception);
        }
    }

    public void stopAndWait() throws OfficeException {
        Future<?> future = executor.submit(new Runnable() {
            public void run() {
                doStopProcess();
            }
        });
        try {
            future.get();
        } catch (Exception exception) {
            throw new OfficeException("failed to start and connect", exception);
        }
    }

    public void restartAndWait() {
        Future<?> future = executor.submit(new Runnable() {
           public void run() {
               doStopProcess();
               doStartProcessAndConnect();
            } 
        });
        try {
            future.get();
        } catch (Exception exception) {
            throw new OfficeException("failed to restart", exception);
        }
    }

    public void restartDueToTaskTimeout() {
        executor.execute(new Runnable() {
           public void run() {
                doTerminateProcess();
                // will cause unexpected disconnection and subsequent restart
            } 
        });
    }

    public void restartDueToLostConnection() {
        executor.execute(new Runnable() {
            public void run() {
                doEnsureProcessExited();
                doStartProcessAndConnect();
            } 
         });
    }

    private void doStartProcessAndConnect() throws OfficeException {
        prepareProfileDir();
        try {
            process.start();
            new Retryable() {
                protected void attempt() throws TemporaryException, Exception {
                    try {
                        connection.connect();
                    } catch (ConnectException connectException) {
                        throw new TemporaryException(connectException);
                    }
                }
            }.execute(configuration.getRetryInterval(), configuration.getRetryTimeout());
        } catch (Exception exception) {
            throw new OfficeException("could not establish connection", exception);
        }
    }

    private void doStopProcess() {
        try {
            XDesktop desktop = OfficeUtils.cast(XDesktop.class, connection.getService(OfficeUtils.SERVICE_DESKTOP));
            desktop.terminate();
        } catch (DisposedException disposedException) {
            // expected
        }
        doEnsureProcessExited();
    }

    private void doDeleteProfileDir() {
        if (profileDir != null) {
            try {
                FileUtils.deleteDirectory(profileDir);
            } catch (IOException ioException) {
                logger.warning(ioException.getMessage());
            }
        }
    }

    private void doEnsureProcessExited() throws OfficeException {
        try {
            int exitCode = process.getExitCode(configuration.getRetryInterval(), configuration.getRetryTimeout());
            logger.info("process exited with code " + exitCode);
        } catch (RetryTimeoutException retryTimeoutException) {
            doTerminateProcess();
        }
        doDeleteProfileDir();
    }

    private void doTerminateProcess() {
        try {
            int exitCode = process.forciblyTerminate(configuration.getRetryInterval(), configuration.getRetryTimeout());
            logger.info("process forcibly terminated with code " + exitCode);
        } catch (Exception exception) {
            throw new OfficeException("could not terminate process", exception);
        }
    }

}