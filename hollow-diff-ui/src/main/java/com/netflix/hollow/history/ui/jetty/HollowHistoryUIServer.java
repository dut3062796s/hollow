/*
 *
 *  Copyright 2016 Netflix, Inc.
 *
 *     Licensed under the Apache License, Version 2.0 (the "License");
 *     you may not use this file except in compliance with the License.
 *     You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *     Unless required by applicable law or agreed to in writing, software
 *     distributed under the License is distributed on an "AS IS" BASIS,
 *     WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *     See the License for the specific language governing permissions and
 *     limitations under the License.
 *
 */
package com.netflix.hollow.history.ui.jetty;

import com.netflix.hollow.history.ui.HollowHistoryUI;
import com.netflix.hollow.tools.history.HollowHistory;

public class HollowHistoryUIServer {
    private static final UIServer.Factory FACTORY = new OptionalDependencyHelper().historyUIServerFactory();

    private final UIServer server;
    private final HollowHistoryUI ui;

    public HollowHistoryUIServer(HollowHistory history, int port) {
        this(new HollowHistoryUI("", history), port);
    }

    public HollowHistoryUIServer(HollowHistoryUI ui, int port) {
        this.server = FACTORY.newServer(ui, port);
        this.ui = ui;
    }

    public void start() throws Exception {
        server.start();
    }

    public void stop() throws Exception {
        server.stop();
    }

    public void join() throws InterruptedException {
        server.join();
    }

    public HollowHistoryUI getUI() {
        return ui;
    }

    static interface UIServer {
        void start() throws Exception;
        void stop() throws Exception;
        void join() throws InterruptedException;

        static interface Factory {
            UIServer newServer(HollowHistoryUI ui, int port);
        }
    }
}
