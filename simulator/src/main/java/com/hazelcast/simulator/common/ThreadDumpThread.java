/*
 * Copyright (c) 2008-2016, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.hazelcast.simulator.common;

import com.hazelcast.simulator.utils.BashCommand;
import org.apache.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.nio.file.Files;
import java.nio.file.Paths;

import static com.hazelcast.simulator.utils.EmptyStatement.ignore;
import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * Checks if the parent process is still running. If not, the current process is terminated.
 *
 * This helps to prevent 'orphan' workers which especially in a local setup are a problem since one can't just kill all
 * java processes since this would also kill the IDE.
 */
public final class ThreadDumpThread extends Thread {
    private static final Logger LOGGER = Logger.getLogger(ThreadDumpThread.class);

    private final String parentPid;
    private final int intervalSeconds;

    public ThreadDumpThread(String parentPid, int intervalSeconds) {
        super("ThreadDumpThread");
        setDaemon(true);
        this.parentPid = parentPid;
        this.intervalSeconds = intervalSeconds;
    }

    @Override
    public void run() {
        if (parentPid == null || intervalSeconds == 0) {
            return;
        }

        try {
            for (; ; ) {
                SECONDS.sleep(intervalSeconds);

                LOGGER.info("Doing thread dump");

                final StringBuilder dump = new StringBuilder();
                final ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
                final ThreadInfo[] threadInfos = threadMXBean.getThreadInfo(threadMXBean.getAllThreadIds(), 100);
                for (ThreadInfo threadInfo : threadInfos) {
                    dump.append('"');
                    dump.append(threadInfo.getThreadName());
                    dump.append("\" ");
                    final Thread.State state = threadInfo.getThreadState();
                    dump.append("\n   java.lang.Thread.State: ");
                    dump.append(state);
                    final StackTraceElement[] stackTraceElements = threadInfo.getStackTrace();
                    for (final StackTraceElement stackTraceElement : stackTraceElements) {
                        dump.append("\n        at ");
                        dump.append(stackTraceElement);
                    }
                    dump.append("\n\n");
                }

                String basename = "thread_dump.txt";
                String filename = basename;
                int i = 0;
                while (new File(filename).exists()) {
                    filename = basename + i;
                    i++;
                }

                Files.write(Paths.get(filename), dump.toString().getBytes());
            }
        } catch (InterruptedException e) {
            ignore(e);
        } catch (IOException e) {
            e.printStackTrace();

        }
    }
}
