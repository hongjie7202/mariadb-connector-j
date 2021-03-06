/*
 *
 * MariaDB Client for Java
 *
 * Copyright (c) 2012-2014 Monty Program Ab.
 * Copyright (c) 2015-2017 MariaDB Ab.
 *
 * This library is free software; you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 2.1 of the License, or (at your option)
 * any later version.
 *
 * This library is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License along
 * with this library; if not, write to Monty Program Ab info@montyprogram.com.
 *
 * This particular MariaDB Client for Java file is work
 * derived from a Drizzle-JDBC. Drizzle-JDBC file which is covered by subject to
 * the following copyright and notice provisions:
 *
 * Copyright (c) 2009-2011, Marcus Eriksson
 *
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 * Redistributions of source code must retain the above copyright notice, this list
 * of conditions and the following disclaimer.
 *
 * Redistributions in binary form must reproduce the above copyright notice, this
 * list of conditions and the following disclaimer in the documentation and/or
 * other materials provided with the distribution.
 *
 * Neither the name of the driver nor the names of its contributors may not be
 * used to endorse or promote products derived from this software without specific
 * prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS  AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 * IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT,
 * INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 * NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY
 * OF SUCH DAMAGE.
 *
 */

package org.mariadb.jdbc.internal.protocol;

import org.mariadb.jdbc.HostAddress;
import org.mariadb.jdbc.MariaDbConnection;
import org.mariadb.jdbc.UrlParser;
import org.mariadb.jdbc.internal.MariaDbServerCapabilities;
import org.mariadb.jdbc.internal.com.read.Buffer;
import org.mariadb.jdbc.internal.com.read.ErrorPacket;
import org.mariadb.jdbc.internal.com.read.OkPacket;
import org.mariadb.jdbc.internal.com.read.ReadInitialHandShakePacket;
import org.mariadb.jdbc.internal.com.read.dao.Results;
import org.mariadb.jdbc.internal.com.read.resultset.SelectResultSet;
import org.mariadb.jdbc.internal.com.send.*;
import org.mariadb.jdbc.internal.failover.FailoverProxy;
import org.mariadb.jdbc.internal.io.LruTraceCache;
import org.mariadb.jdbc.internal.io.input.DecompressPacketInputStream;
import org.mariadb.jdbc.internal.io.input.PacketInputStream;
import org.mariadb.jdbc.internal.io.input.StandardPacketInputStream;
import org.mariadb.jdbc.internal.io.output.CompressPacketOutputStream;
import org.mariadb.jdbc.internal.io.output.PacketOutputStream;
import org.mariadb.jdbc.internal.io.output.StandardPacketOutputStream;
import org.mariadb.jdbc.internal.logging.Logger;
import org.mariadb.jdbc.internal.logging.LoggerFactory;
import org.mariadb.jdbc.internal.protocol.authentication.AuthenticationProviderHolder;
import org.mariadb.jdbc.internal.protocol.authentication.DefaultAuthenticationProvider;
import org.mariadb.jdbc.internal.protocol.tls.HostnameVerifierImpl;
import org.mariadb.jdbc.internal.protocol.tls.MariaDbX509KeyManager;
import org.mariadb.jdbc.internal.protocol.tls.MariaDbX509TrustManager;
import org.mariadb.jdbc.internal.util.Options;
import org.mariadb.jdbc.internal.util.ServerPrepareStatementCache;
import org.mariadb.jdbc.internal.util.Utils;
import org.mariadb.jdbc.internal.util.constant.HaMode;
import org.mariadb.jdbc.internal.util.constant.ParameterConstant;
import org.mariadb.jdbc.internal.util.constant.ServerStatus;
import org.mariadb.jdbc.internal.util.exceptions.ExceptionMapper;

import javax.net.ssl.*;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.NoSuchAlgorithmException;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

import static org.mariadb.jdbc.internal.com.Packet.*;

public abstract class AbstractConnectProtocol implements Protocol {
    private static final byte[] SESSION_QUERY = ("SELECT @@max_allowed_packet,"
            + "@@system_time_zone,"
            + "@@time_zone,"
            + "@@auto_increment_increment").getBytes(StandardCharsets.UTF_8);
    private static final byte[] IS_MASTER_QUERY = "show global variables like 'innodb_read_only'".getBytes(StandardCharsets.UTF_8);
    private static final Logger logger = LoggerFactory.getLogger(AbstractConnectProtocol.class);
    protected final ReentrantLock lock;
    protected final UrlParser urlParser;
    protected final Options options;
    private final String username;
    private final String password;
    public boolean hasWarnings = false;
    public Results activeStreamingResult = null;
    private int dataTypeMappingFlags;
    public short serverStatus;
    protected int autoIncrementIncrement;
    protected Socket socket;
    protected PacketOutputStream writer;
    protected boolean readOnly = false;
    protected PacketInputStream reader;
    private HostAddress currentHost;
    protected FailoverProxy proxy;
    protected volatile boolean connected = false;
    protected boolean explicitClosed = false;
    protected String database;
    protected long serverThreadId;
    protected ServerPrepareStatementCache serverPrepareStatementCache;
    protected boolean eofDeprecated = false;
    protected long serverCapabilities;
    private boolean hostFailed;
    private String serverVersion;
    private boolean serverMariaDb;
    private int majorVersion;
    private int minorVersion;
    private int patchVersion;
    private TimeZone timeZone;
    private final LruTraceCache traceCache = new LruTraceCache();

    /**
     * Get a protocol instance.
     *
     * @param urlParser connection URL infos
     * @param lock      the lock for thread synchronisation
     */

    public AbstractConnectProtocol(final UrlParser urlParser, final ReentrantLock lock) {
        urlParser.auroraPipelineQuirks();
        this.lock = lock;
        this.urlParser = urlParser;
        this.options = urlParser.getOptions();
        this.database = (urlParser.getDatabase() == null ? "" : urlParser.getDatabase());
        this.username = (urlParser.getUsername() == null ? "" : urlParser.getUsername());
        this.password = (urlParser.getPassword() == null ? "" : urlParser.getPassword());
        if (options.cachePrepStmts) {
            serverPrepareStatementCache = ServerPrepareStatementCache.newInstance(options.prepStmtCacheSize, this);
        }

        setDataTypeMappingFlags();
    }

    private static void close(PacketInputStream packetInputStream, PacketOutputStream packetOutputStream, Socket socket) throws SQLException {
        SendClosePacket closePacket = new SendClosePacket();
        try {
            try {
                closePacket.send(packetOutputStream);
                socket.shutdownOutput();
                socket.setSoTimeout(3);
                InputStream is = socket.getInputStream();
                //noinspection StatementWithEmptyBody
                while (is.read() != -1) {
                    //read byte
                }
            } catch (Throwable t) {
                //eat exception
            }
            packetOutputStream.close();
            packetInputStream.close();
        } catch (IOException e) {
            throw ExceptionMapper.connException("Could not close connection: " + e.getMessage(), e);
        } finally {
            try {
                socket.close();
            } catch (IOException e) {
                //socket closed, if any error, so not throwing error
            }
        }
    }

    /**
     * Closes socket and stream readers/writers Attempts graceful shutdown.
     */
    public void close() {
        if (lock != null) lock.lock();
        this.connected = false;
        try {
            /* If a streaming result set is open, close it.*/
            skip();
        } catch (Exception e) {
            /* eat exception */
        }
        try {
            if (options.cachePrepStmts) {
                serverPrepareStatementCache.clear();
            }
            close(reader, writer, socket);
        } catch (Exception e) {
            // socket is closed, so it is ok to ignore exception
        } finally {
            if (lock != null) lock.unlock();
        }

        if (options.enablePacketDebug) {
            traceCache.clearMemory();
        }
    }

    /**
     * Skip packets not read that are not needed.
     * Packets are read according to needs.
     * If some data have not been read before next execution, skip it.
     * <p>
     * <i>Lock must be set before using this method</i>
     *
     * @throws SQLException exception
     */
    public void skip() throws SQLException {
        if (activeStreamingResult != null) {
            activeStreamingResult.loadFully(true, this);
            activeStreamingResult = null;
        }
    }

    public void setServerStatus(short serverStatus) {
        this.serverStatus = serverStatus;
    }

    public void removeHasMoreResults() {
        if (hasMoreResults()) this.serverStatus = (short) (serverStatus ^ ServerStatus.MORE_RESULTS_EXISTS);
    }

    private SSLSocketFactory getSslSocketFactory() throws SQLException {
        if (!options.trustServerCertificate
                && options.serverSslCert == null
                && options.trustStore == null
                && options.keyStore == null) {
            return (SSLSocketFactory) SSLSocketFactory.getDefault();
        }

        TrustManager[] trustManager = null;
        KeyManager[] keyManager = null;

        if (options.trustServerCertificate || options.serverSslCert != null || options.trustStore != null) {
            trustManager = new X509TrustManager[]{new MariaDbX509TrustManager(options)};
        }

        if (options.keyStore != null) {
            keyManager = new KeyManager[]{loadClientCerts(options.keyStore, options.keyStorePassword, options.keyPassword)};
        } else {
            String keyStore = System.getProperty("javax.net.ssl.keyStore");
            String keyStorePassword = System.getProperty("javax.net.ssl.keyStorePassword");
            if (keyStore != null) {
                try {
                    keyManager = new KeyManager[]{loadClientCerts(keyStore, keyStorePassword, keyStorePassword)};
                } catch (SQLException queryException) {
                    keyManager = null;
                    logger.error("Error loading keymanager from system properties", queryException);
                }
            }
        }

        try {
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(keyManager, trustManager, null);
            return sslContext.getSocketFactory();
        } catch (KeyManagementException keyManagementEx) {
            throw ExceptionMapper.connException("Could not initialize SSL context", keyManagementEx);
        } catch (NoSuchAlgorithmException noSuchAlgorithmEx) {
            throw ExceptionMapper.connException("SSLContext TLS Algorithm not unknown", noSuchAlgorithmEx);
        }

    }

    private KeyManager loadClientCerts(String keyStoreUrl, String keyStorePassword, String keyPassword) throws SQLException {
        InputStream inStream = null;
        try {

            char[] keyStorePasswordChars = keyStorePassword == null ? null : keyStorePassword.toCharArray();

            try {
                inStream = new URL(keyStoreUrl).openStream();
            } catch (IOException ioexception) {
                inStream = new FileInputStream(keyStoreUrl);
            }

            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
            ks.load(inStream, keyStorePasswordChars);
            char[] keyStoreChars = (keyPassword == null) ? keyStorePasswordChars : keyPassword.toCharArray();
            return new MariaDbX509KeyManager(ks, keyStoreChars);
        } catch (GeneralSecurityException generalSecurityEx) {
            throw ExceptionMapper.connException("Failed to create keyStore instance", generalSecurityEx);
        } catch (FileNotFoundException fileNotFoundEx) {
            throw ExceptionMapper.connException("Failed to find keyStore file. Option keyStore=" + keyStoreUrl, fileNotFoundEx);
        } catch (IOException ioEx) {
            throw ExceptionMapper.connException("Failed to read keyStore file. Option keyStore=" + keyStoreUrl, ioEx);
        } finally {
            try {
                if (inStream != null) inStream.close();
            } catch (IOException ioEx) {
                //ignore error
            }
        }

    }

    /**
     * InitializeSocketOption.
     */
    private void initializeSocketOption() {
        try {

            socket.setTcpNoDelay(options.tcpNoDelay);

            if (options.tcpKeepAlive) {
                socket.setKeepAlive(true);
            }
            if (options.tcpRcvBuf != null) {
                socket.setReceiveBufferSize(options.tcpRcvBuf);
            }
            if (options.tcpSndBuf != null) {
                socket.setSendBufferSize(options.tcpSndBuf);
            }
            if (options.tcpAbortiveClose) {
                socket.setSoLinger(true, 0);
            }
        } catch (Exception e) {
            logger.debug("Failed to set socket option", e);
        }
    }

    /**
     * Connect to currentHost.
     *
     * @throws SQLException exception
     */
    public void connect() throws SQLException {
        if (!isClosed()) close();

        try {
            connect((currentHost != null) ? currentHost.host : null,
                    (currentHost != null) ? currentHost.port : 3306);
        } catch (IOException ioException) {
            throw ExceptionMapper.connException(
                    "Could not connect to " + currentHost + ". " + ioException.getMessage() + getTraces(),
                    ioException);
        }
    }

    /**
     * Connect the client and perform handshake.
     *
     * @param host host
     * @param port port
     * @throws SQLException handshake error, e.g wrong user or password
     * @throws IOException  connection error (host/port not available)
     */
    private void connect(String host, int port) throws SQLException, IOException {
        try {
            socket = Utils.createSocket(urlParser, host);

            initializeSocketOption();

            // Bind the socket to a particular interface if the connection property
            // localSocketAddress has been defined.
            if (options.localSocketAddress != null) {
                InetSocketAddress localAddress = new InetSocketAddress(options.localSocketAddress, 0);
                socket.bind(localAddress);
            }

            if (!socket.isConnected()) {
                InetSocketAddress sockAddr = urlParser.getOptions().pipe == null ? new InetSocketAddress(host, port) : null;
                if (options.connectTimeout != null) {
                    socket.connect(sockAddr, options.connectTimeout);
                } else {
                    socket.connect(sockAddr);
                }
            }

            handleConnectionPhases(host);

            connected = true;

            if (options.useCompression) {
                writer = new CompressPacketOutputStream(writer.getOutputStream(), options.maxQuerySizeToLog);
                reader = new DecompressPacketInputStream(((StandardPacketInputStream) reader).getBufferedInputStream(), options.maxQuerySizeToLog);
                if (options.enablePacketDebug) {
                    writer.setTraceCache(traceCache);
                    reader.setTraceCache(traceCache);
                }
            }

            Map<String, String> serverData = new TreeMap<>();
            if (options.usePipelineAuth && !options.createDatabaseIfNotExist) {
                try {
                    sendPipelineAdditionalData();
                    readPipelineAdditionalData(serverData);
                } catch (SQLException sqle) {
                    //in case pipeline is not supported
                    //(proxy flush socket after reading first packet)
                    additionalData(serverData);
                }
            } else additionalData(serverData);

            // Extract socketTimeout URL parameter
            if (options.socketTimeout != null) socket.setSoTimeout(options.socketTimeout);

            writer.setMaxAllowedPacket(Integer.parseInt(serverData.get("max_allowed_packet")));
            autoIncrementIncrement = Integer.parseInt(serverData.get("auto_increment_increment"));

            loadCalendar(serverData);

            reader.setServerThreadId(this.serverThreadId, isMasterConnection());
            writer.setServerThreadId(this.serverThreadId, isMasterConnection());

            activeStreamingResult = null;
            hostFailed = false;
        } catch (IOException | SQLException ioException) {
            ensureClosingSocketOnException();
            throw ioException;
        }
    }

    /**
     * Send all additional needed values.
     * Command are send one after the other, assuming that command are less than 65k
     * (minimum hosts TCP/IP buffer size)
     *
     * @throws IOException  if socket exception occur
     */
    private void sendPipelineAdditionalData() throws IOException {
        sendSessionInfos();
        sendRequestSessionVariables();
        //for aurora, check that connection is master
        sendPipelineCheckMaster();
    }

    private void sendSessionInfos() throws IOException {
        // In JDBC, connection must start in autocommit mode
        // [CONJ-269] we cannot rely on serverStatus & ServerStatus.AUTOCOMMIT before this command to avoid this command.
        // if autocommit=0 is set on server configuration, DB always send Autocommit on serverStatus flag
        // after setting autocommit, we can rely on serverStatus value
        StringBuilder sessionOption = new StringBuilder("autocommit=1");
        if ((serverCapabilities & MariaDbServerCapabilities.CLIENT_SESSION_TRACK) != 0) {
            sessionOption.append(", session_track_schema=1");
            if (options.rewriteBatchedStatements) {
                sessionOption.append(", session_track_system_variables='auto_increment_increment' ");
            }
        }

        if (options.jdbcCompliantTruncation) {
            sessionOption.append(", sql_mode = concat(@@sql_mode,',STRICT_TRANS_TABLES')");
        }

        if (options.sessionVariables != null && !options.sessionVariables.isEmpty()) {
            sessionOption.append(",").append(Utils.parseSessionVariables(options.sessionVariables));
        }

        writer.startPacket(0);
        writer.write(COM_QUERY);
        writer.write("set " + sessionOption.toString());
        writer.flush();
    }

    private void sendRequestSessionVariables() throws IOException {
        writer.startPacket(0);
        writer.write(COM_QUERY);
        writer.write(SESSION_QUERY);
        writer.flush();
    }

    private void readRequestSessionVariables(Map<String, String> serverData) throws SQLException {
        Results results = new Results();
        getResult(results);

        results.commandEnd();
        ResultSet resultSet = results.getResultSet();
        if (resultSet != null) {
            resultSet.next();

            serverData.put("max_allowed_packet", resultSet.getString(1));
            serverData.put("system_time_zone", resultSet.getString(2));
            serverData.put("time_zone", resultSet.getString(3));
            serverData.put("auto_increment_increment", resultSet.getString(4));

        } else {
            throw new SQLException("Error reading SessionVariables results. Socket is connected ? "
                    + socket.isConnected());
        }
    }

    private void sendCreateDatabaseIfNotExist(String quotedDb) throws IOException {
        writer.startPacket(0);
        writer.write(COM_QUERY);
        writer.write("CREATE DATABASE IF NOT EXISTS " + quotedDb);
        writer.flush();
    }

    private void sendUseDatabaseIfNotExist(String quotedDb) throws IOException {
        writer.startPacket(0);
        writer.write(COM_QUERY);
        writer.write("USE " + quotedDb);
        writer.flush();
    }

    private void readPipelineAdditionalData(Map<String, String> serverData) throws SQLException {

        SQLException resultingException = null;
        //read set session OKPacket
        try {
            getResult(new Results());
        } catch (SQLException sqlException) {
            //must read all results, will be thrown only when all results are read.
            resultingException = sqlException;
        }

        boolean canTrySessionWithShow = false;
        try {
            readRequestSessionVariables(serverData);
        } catch (SQLException sqlException) {
            if (resultingException == null) {
                resultingException = ExceptionMapper.connException("could not load system variables", sqlException);
                canTrySessionWithShow = true;
            }
        }

        try {
            readPipelineCheckMaster();
        } catch (SQLException sqlException) {
            canTrySessionWithShow = false;
            if (resultingException == null) {
                throw ExceptionMapper.connException("could not identified if server is master", sqlException);
            }
        }

        if (canTrySessionWithShow) {
            //fallback in case of galera non primary nodes that permit only show / set command,
            //not SELECT when not part of quorum
            requestSessionDataWithShow(serverData);
        }

        if (resultingException != null) throw resultingException;
        connected = true;

    }

    private void requestSessionDataWithShow(Map<String, String> serverData) throws SQLException {
        try {
            Results results = new Results();
            executeQuery(true, results, "SHOW VARIABLES WHERE Variable_name in ("
                    + "'max_allowed_packet',"
                    + "'system_time_zone',"
                    + "'time_zone',"
                    + "'auto_increment_increment')");
            results.commandEnd();
            ResultSet resultSet = results.getResultSet();

            while (resultSet.next()) {
                if (logger.isDebugEnabled()) {
                    logger.debug("server data {} = {}",
                            resultSet.getString(1),
                            resultSet.getString(2));
                }
                serverData.put(resultSet.getString(1), resultSet.getString(2));
            }
        } catch (SQLException sqlException) {
            throw ExceptionMapper.connException("could not load system variables", sqlException);
        }
    }

    private void additionalData(Map<String, String> serverData) throws IOException, SQLException {

        sendSessionInfos();
        getResult(new Results());

        try {
            sendRequestSessionVariables();
            readRequestSessionVariables(serverData);
        } catch (SQLException sqlException) {
            requestSessionDataWithShow(serverData);
        }

        //for aurora, check that connection is master
        sendPipelineCheckMaster();
        readPipelineCheckMaster();

        if (options.createDatabaseIfNotExist && !database.isEmpty()) {
            // Try to create the database if it does not exist
            String quotedDb = MariaDbConnection.quoteIdentifier(this.database);
            sendCreateDatabaseIfNotExist(quotedDb);
            getResult(new Results());

            sendUseDatabaseIfNotExist(quotedDb);
            getResult(new Results());
        }
    }

    private void ensureClosingSocketOnException() {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ioe) {
                //eat exception
            }
        }
    }

    /**
     * Is the connection closed.
     *
     * @return true if the connection is closed
     */
    public boolean isClosed() {
        return !this.connected;
    }

    private void handleConnectionPhases(String host) throws SQLException {
        try {
            reader = new StandardPacketInputStream(socket.getInputStream(), options.maxQuerySizeToLog);
            writer = new StandardPacketOutputStream(socket.getOutputStream(), options.maxQuerySizeToLog);

            if (options.enablePacketDebug) {
                writer.setTraceCache(traceCache);
                reader.setTraceCache(traceCache);
            }

            final ReadInitialHandShakePacket greetingPacket = new ReadInitialHandShakePacket(reader);
            this.serverThreadId = greetingPacket.getServerThreadId();
            reader.setServerThreadId(this.serverThreadId, null);
            writer.setServerThreadId(this.serverThreadId, null);

            this.serverVersion = greetingPacket.getServerVersion();
            this.serverMariaDb = greetingPacket.isServerMariaDb();
            this.serverCapabilities = greetingPacket.getServerCapabilities();

            byte exchangeCharset = decideLanguage(greetingPacket.getServerLanguage());
            parseVersion();
            long clientCapabilities = initializeClientCapabilities(serverCapabilities);

            byte packetSeq = 1;
            if (options.useSsl && (greetingPacket.getServerCapabilities() & MariaDbServerCapabilities.SSL) != 0) {
                clientCapabilities |= MariaDbServerCapabilities.SSL;
                SendSslConnectionRequestPacket amcap = new SendSslConnectionRequestPacket(clientCapabilities, exchangeCharset);
                amcap.send(writer);

                SSLSocketFactory sslSocketFactory = getSslSocketFactory();
                SSLSocket sslSocket = (SSLSocket) sslSocketFactory.createSocket(socket,
                        socket.getInetAddress().getHostAddress(), socket.getPort(), true);

                enabledSslProtocolSuites(sslSocket);
                enabledSslCipherSuites(sslSocket);

                sslSocket.setUseClientMode(true);
                sslSocket.startHandshake();


                // perform hostname verification
                // (rfc2818 indicate that if "client has external information as to the expected identity of the server,
                // the hostname check MAY be omitted")
                if (!options.disableSslHostnameVerification && !options.trustServerCertificate) {
                    HostnameVerifierImpl hostnameVerifier = new HostnameVerifierImpl();
                    SSLSession session = sslSocket.getSession();
                    if (!hostnameVerifier.verify(host, session, serverThreadId)) {

                        //Use proprietary verify method in order to have an exception with a better description of error.
                        try {
                            Certificate[] certs = session.getPeerCertificates();
                            X509Certificate cert = (X509Certificate) certs[0];
                            hostnameVerifier.verify(host, cert, serverThreadId);
                        } catch (SSLException ex) {
                            throw new SQLNonTransientConnectionException("SSL hostname verification failed : " + ex.getMessage()
                                    + "\nThis verification can be disable using the option \"disableSslHostnameVerification\" "
                                    + "but won't prevent man-in-the-middle attacks anymore", "08006");
                        }
                    }
                }

                socket = sslSocket;
                writer = new StandardPacketOutputStream(socket.getOutputStream(), options.maxQuerySizeToLog);
                reader = new StandardPacketInputStream(socket.getInputStream(), options.maxQuerySizeToLog);

                if (options.enablePacketDebug) {
                    writer.setTraceCache(traceCache);
                    reader.setTraceCache(traceCache);
                }
                packetSeq++;
            } else if (options.useSsl) {
                throw new SQLException("Trying to connect with ssl, but ssl not enabled in the server");
            }

            authentication(exchangeCharset, clientCapabilities, packetSeq, greetingPacket);

        } catch (IOException ioException) {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException ee) {
                    //eat exception
                }
            }

            throw ExceptionMapper.connException(
                    "Could not connect to " + currentHost.host + ":" + currentHost.port + " : " + ioException.getMessage(),
                    ioException);
        }
    }

    private void authentication(byte exchangeCharset, long clientCapabilities, byte packetSeq, ReadInitialHandShakePacket greetingPacket)
            throws SQLException, IOException {

        //send handshake response
        SendHandshakeResponsePacket.send(writer, this.username,
                this.password,
                database,
                clientCapabilities,
                serverCapabilities,
                exchangeCharset,
                packetSeq,
                options,
                greetingPacket);

        Buffer buffer = reader.getPacket(false);

        if ((buffer.getByteAt(0) & 0xFF) == 0xFE) {

            writer.permitTrace(false);
            InterfaceAuthSwitchSendResponsePacket interfaceSendPacket;
            if ((serverCapabilities & MariaDbServerCapabilities.PLUGIN_AUTH) != 0) {
                buffer.readByte();
                byte[] authData;
                String plugin;
                if (buffer.remaining() > 0) {
                    //AuthSwitchRequest packet.
                    plugin = buffer.readStringNullEnd(Charset.forName("ASCII"));
                    authData = buffer.readRawBytes(buffer.remaining());
                } else {
                    //OldAuthSwitchRequest
                    plugin = DefaultAuthenticationProvider.MYSQL_OLD_PASSWORD;
                    authData = Utils.copyWithLength(greetingPacket.getSeed(), 8);
                }

                //Authentication according to plugin.
                //see AuthenticationProviderHolder for implement other plugin
                interfaceSendPacket = AuthenticationProviderHolder.getAuthenticationProvider()
                        .processAuthPlugin(reader, plugin, password, authData, reader.getLastPacketSeq() + 1,
                                options.passwordCharacterEncoding);
            } else {
                interfaceSendPacket = new SendOldPasswordAuthPacket(this.password, Utils.copyWithLength(greetingPacket.getSeed(), 8),
                        reader.getLastPacketSeq() + 1, options.passwordCharacterEncoding);
            }
            interfaceSendPacket.send(writer);
            interfaceSendPacket.handleResultPacket(reader);

        } else {
            if (buffer.getByteAt(0) == ERROR) {
                ErrorPacket errorPacket = new ErrorPacket(buffer);
                if (password != null && !password.isEmpty() && errorPacket.getErrorNumber() == 1045 && "28000".equals(errorPacket.getSqlState())) {
                    //Access denied
                    throw new SQLException(errorPacket.getMessage()
                            + "\nCurrent charset is " + Charset.defaultCharset().displayName()
                            + ". If password has been set using other charset, consider "
                            + "using option 'passwordCharacterEncoding'",
                            errorPacket.getSqlState(), errorPacket.getErrorNumber());
                }
                throw new SQLException(errorPacket.getMessage(), errorPacket.getSqlState(), errorPacket.getErrorNumber());
            }
            serverStatus = new OkPacket(buffer).getServerStatus();
        }

        writer.permitTrace(true);

    }

    private long initializeClientCapabilities(long serverCapabilities) {
        long capabilities = MariaDbServerCapabilities.IGNORE_SPACE
                | MariaDbServerCapabilities.CLIENT_PROTOCOL_41
                | MariaDbServerCapabilities.TRANSACTIONS
                | MariaDbServerCapabilities.SECURE_CONNECTION
                | MariaDbServerCapabilities.LOCAL_FILES
                | MariaDbServerCapabilities.MULTI_RESULTS
                | MariaDbServerCapabilities.PS_MULTI_RESULTS
                | MariaDbServerCapabilities.FOUND_ROWS
                | MariaDbServerCapabilities.PLUGIN_AUTH
                | MariaDbServerCapabilities.CONNECT_ATTRS
                | MariaDbServerCapabilities.PLUGIN_AUTH_LENENC_CLIENT_DATA
                | MariaDbServerCapabilities.CLIENT_SESSION_TRACK;

        if (options.allowMultiQueries || (options.rewriteBatchedStatements)) {
            capabilities |= MariaDbServerCapabilities.MULTI_STATEMENTS;
        }

        if ((serverCapabilities & MariaDbServerCapabilities.CLIENT_DEPRECATE_EOF) != 0) {
            capabilities |= MariaDbServerCapabilities.CLIENT_DEPRECATE_EOF;
            eofDeprecated = true;
        }

        if (options.useCompression) {
            if ((serverCapabilities & MariaDbServerCapabilities.COMPRESS) == 0) {
                //ensure that server has compress capacity - MaxScale doesn't
                options.useCompression = false;
            } else {
                capabilities |= MariaDbServerCapabilities.COMPRESS;
            }
        }

        if (options.interactiveClient) {
            capabilities |= MariaDbServerCapabilities.CLIENT_INTERACTIVE;
        }

        // If a database is given, but createDatabaseIfNotExist is not defined or is false,
        // then just try to connect to the given database
        if (!database.isEmpty() && !options.createDatabaseIfNotExist) {
            capabilities |= MariaDbServerCapabilities.CONNECT_WITH_DB;
        }
        return capabilities;
    }

    private void loadCalendar(Map<String, String> serverData) throws SQLException {
        if (options.useLegacyDatetimeCode) {
            //legacy use client timezone
            timeZone = Calendar.getInstance().getTimeZone();
        } else {
            //use server time zone
            String tz = null;
            if (options.serverTimezone != null) {
                tz = options.serverTimezone;
            }

            if (tz == null) {
                tz = serverData.get("time_zone");
                if ("SYSTEM".equals(tz)) {
                    tz = serverData.get("system_time_zone");
                }
            }
            //handle custom timezone id
            if (tz != null && tz.length() >= 2
                    && (tz.startsWith("+") || tz.startsWith("-"))
                    && Character.isDigit(tz.charAt(1))) {
                tz = "GMT" + tz;
            }

            try {
                timeZone = Utils.getTimeZone(tz);
            } catch (SQLException e) {
                if (options.serverTimezone != null) {
                    throw new SQLException("The server time_zone '" + tz + "' defined in the 'serverTimezone' parameter cannot be parsed "
                            + "by java TimeZone implementation. See java.util.TimeZone#getAvailableIDs() for available TimeZone, depending on your "
                            + "JRE implementation.", "01S00");
                } else {
                    throw new SQLException("The server time_zone '" + tz + "' cannot be parsed. The server time zone must defined in the "
                            + "jdbc url string with the 'serverTimezone' parameter (or server time zone must be defined explicitly with "
                            + "sessionVariables=time_zone='Canada/Atlantic' for example).  See "
                            + "java.util.TimeZone#getAvailableIDs() for available TimeZone, depending on your JRE implementation.", "01S00");
                }
            }
        }

    }

    /**
     * Check that current connection is a master connection (not read-only)
     *
     * @return true if master
     * @throws SQLException if requesting infos for server fail.
     */
    public boolean checkIfMaster() throws SQLException {
        return isMasterConnection();
    }

    private boolean isServerLanguageUtf8mb4(byte serverLanguage) {
        Byte[] utf8mb4Languages = {
                (byte) 45, (byte) 46, (byte) 224, (byte) 225, (byte) 226, (byte) 227, (byte) 228,
                (byte) 229, (byte) 230, (byte) 231, (byte) 232, (byte) 233, (byte) 234, (byte) 235,
                (byte) 236, (byte) 237, (byte) 238, (byte) 239, (byte) 240, (byte) 241, (byte) 242,
                (byte) 243, (byte) 245, (byte) 246, (byte) 247
        };
        return Arrays.asList(utf8mb4Languages).contains(serverLanguage);
    }

    private byte decideLanguage(byte serverLanguage) {
        //force UTF8mb4 if possible, UTF8 if not.
        return (isServerLanguageUtf8mb4(serverLanguage) ? serverLanguage : 33);
    }

    /**
     * Check that next read packet is a End-of-file packet.
     *
     * @throws SQLException if not a End-of-file packet
     * @throws IOException  if connection error occur
     */
    public void readEofPacket() throws SQLException, IOException {
        Buffer buffer = reader.getPacket(true);
        switch (buffer.getByteAt(0)) {
            case EOF:
                buffer.skipByte();
                this.hasWarnings = buffer.readShort() > 0;
                this.serverStatus = buffer.readShort();
                break;

            case ERROR:
                ErrorPacket ep = new ErrorPacket(buffer);
                throw new SQLException("Could not connect: " + ep.getMessage(), ep.getSqlState(), ep.getErrorNumber());

            default:
                throw new SQLException("Unexpected packet type " + buffer.getByteAt(0)
                        + " instead of EOF");
        }
    }

    /**
     * Check that next read packet is a End-of-file packet.
     *
     * @throws SQLException if not a End-of-file packet
     * @throws IOException  if connection error occur
     */
    public void skipEofPacket() throws SQLException, IOException {
        Buffer buffer = reader.getPacket(true);
        switch (buffer.getByteAt(0)) {
            case EOF:
                break;

            case ERROR:
                ErrorPacket ep = new ErrorPacket(buffer);
                throw new SQLException("Could not connect: " + ep.getMessage(), ep.getSqlState(), ep.getErrorNumber());

            default:
                throw new SQLException("Unexpected packet type " + buffer.getByteAt(0)
                        + " instead of EOF");
        }
    }

    public void setHostFailedWithoutProxy() {
        hostFailed = true;
        close();
    }

    public UrlParser getUrlParser() {
        return urlParser;
    }

    /**
     * Indicate if current protocol is a master protocol.
     *
     * @return is master flag
     */
    public boolean isMasterConnection() {
        return currentHost == null || ParameterConstant.TYPE_MASTER.equals(currentHost.type);
    }

    /**
     * Send query to identify if server is master.
     *
     * @throws IOException in case of socket error.
     */
    private void sendPipelineCheckMaster() throws IOException {
        if (urlParser.getHaMode() == HaMode.AURORA) {
            writer.startPacket(0);
            writer.write(COM_QUERY);
            writer.write(IS_MASTER_QUERY);
            writer.flush();
        }
    }

    public void readPipelineCheckMaster() throws SQLException {
        //nothing if not aurora
    }

    public boolean mustBeMasterConnection() {
        return true;
    }

    public boolean noBackslashEscapes() {
        return ((serverStatus & ServerStatus.NO_BACKSLASH_ESCAPES) != 0);
    }

    /**
     * Connect without proxy. (use basic failover implementation)
     *
     * @throws SQLException exception
     */
    public void connectWithoutProxy() throws SQLException {
        if (!isClosed()) {
            close();
        }

        List<HostAddress> addrs = urlParser.getHostAddresses();
        LinkedList<HostAddress> hosts = new LinkedList<>(addrs);

        if (urlParser.getHaMode().equals(HaMode.LOADBALANCE)) {
            Collections.shuffle(hosts);
        }

        //CONJ-293 : handle name-pipe without host
        if (hosts.isEmpty() && options.pipe != null) {
            try {
                connect(null, 0);
                return;
            } catch (IOException ioException) {
                throw ExceptionMapper.connException(
                        "Could not connect to named pipe '" + options.pipe + "' : " + ioException.getMessage() + getTraces(),
                        ioException);
            }
        }

        // There could be several addresses given in the URL spec, try all of them, and throw exception if all hosts
        // fail.
        while (!hosts.isEmpty()) {
            currentHost = hosts.poll();
            try {
                connect(currentHost.host, currentHost.port);
                return;
            } catch (SQLException e) {
                if (hosts.isEmpty()) {
                    throw ExceptionMapper.getException(e, null, null, false);
                }
            } catch (IOException ioException) {
                if (hosts.isEmpty()) {
                    throw ExceptionMapper.connException(
                            "Could not connect to " + HostAddress.toString(addrs) + " : " + ioException.getMessage() + getTraces(),
                            ioException);
                }
            }
        }
    }

    public boolean shouldReconnectWithoutProxy() {
        return (((serverStatus & ServerStatus.IN_TRANSACTION) == 0) && hostFailed && urlParser.getOptions().autoReconnect);
    }

    public String getServerVersion() {
        return serverVersion;
    }

    public boolean getReadonly() {
        return readOnly;
    }

    public void setReadonly(final boolean readOnly) {
        this.readOnly = readOnly;
    }

    public HostAddress getHostAddress() {
        return currentHost;
    }

    public void setHostAddress(HostAddress host) {
        this.currentHost = host;
        this.readOnly = ParameterConstant.TYPE_SLAVE.equals(this.currentHost.type);
    }

    public String getHost() {
        return (currentHost == null) ? null : currentHost.host;
    }

    public FailoverProxy getProxy() {
        return proxy;
    }

    public void setProxy(FailoverProxy proxy) {
        this.proxy = proxy;
    }

    public int getPort() {
        return (currentHost == null) ? 3306 : currentHost.port;
    }

    public String getDatabase() {
        return database;
    }

    public String getUsername() {
        return username;
    }

    private void parseVersion() {
        String[] versionArray = serverVersion.split("[^0-9]");

        //standard version
        if (versionArray.length > 2) {
            majorVersion = Integer.parseInt(versionArray[0]);
            minorVersion = Integer.parseInt(versionArray[1]);
            patchVersion = Integer.parseInt(versionArray[2]);
            return;
        }

        // in case version string has been forced
        if (versionArray.length > 0) {
            majorVersion = Integer.parseInt(versionArray[0]);
        }

        if (versionArray.length > 1) {
            minorVersion = Integer.parseInt(versionArray[1]);
        }

    }

    public int getMajorServerVersion() {
        return majorVersion;

    }

    public int getMinorServerVersion() {
        return minorVersion;
    }

    /**
     * Return possible protocols : values of option enabledSslProtocolSuites is set, or default to "TLSv1,TLSv1.1".
     * MariaDB versions &ge; 10.0.15 and &ge; 5.5.41 supports TLSv1.2 if compiled with openSSL (default).
     * MySQL community versions &ge; 5.7.10 is compile with yaSSL, so max TLS is TLSv1.1.
     *
     * @param sslSocket current sslSocket
     * @throws SQLException if protocol isn't a supported protocol
     */
    private void enabledSslProtocolSuites(SSLSocket sslSocket) throws SQLException {
        if (options.enabledSslProtocolSuites == null) {
            sslSocket.setEnabledProtocols(new String[]{"TLSv1", "TLSv1.1"});
        } else {
            List<String> possibleProtocols = Arrays.asList(sslSocket.getSupportedProtocols());
            String[] protocols = options.enabledSslProtocolSuites.split("[,;\\s]+");
            for (String protocol : protocols) {
                if (!possibleProtocols.contains(protocol)) {
                    throw new SQLException("Unsupported SSL protocol '" + protocol + "'. Supported protocols : "
                            + possibleProtocols.toString().replace("[", "").replace("]", ""));
                }
            }
            sslSocket.setEnabledProtocols(protocols);
        }
    }

    /**
     * Set ssl socket cipher according to options.
     *
     * @param sslSocket current ssl socket
     * @throws SQLException if a cipher isn't known
     */
    private void enabledSslCipherSuites(SSLSocket sslSocket) throws SQLException {
        if (options.enabledSslCipherSuites != null) {
            List<String> possibleCiphers = Arrays.asList(sslSocket.getSupportedCipherSuites());
            String[] ciphers = options.enabledSslCipherSuites.split("[,;\\s]+");
            for (String cipher : ciphers) {
                if (!possibleCiphers.contains(cipher)) {
                    throw new SQLException("Unsupported SSL cipher '" + cipher + "'. Supported ciphers : "
                            + possibleCiphers.toString().replace("[", "").replace("]", ""));
                }
            }
            sslSocket.setEnabledCipherSuites(ciphers);
        }
    }

    /**
     * Utility method to check if database version is greater than parameters.
     *
     * @param major major version
     * @param minor minor version
     * @param patch patch version
     * @return true if version is greater than parameters
     */
    public boolean versionGreaterOrEqual(int major, int minor, int patch) {
        if (this.majorVersion > major) {
            return true;
        }

        if (this.majorVersion < major) {
            return false;
        }

        /*
         * Major versions are equal, compare minor versions
        */
        if (this.minorVersion > minor) {
            return true;
        }
        if (this.minorVersion < minor) {
            return false;
        }

        //Minor versions are equal, compare patch version.
        return this.patchVersion >= patch;
    }

    public boolean getPinGlobalTxToPhysicalConnection() {
        return this.options.pinGlobalTxToPhysicalConnection;
    }

    /**
     * Has warnings.
     *
     * @return true if as warnings.
     */
    public boolean hasWarnings() {
        lock.lock();
        try {
            return hasWarnings;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Is connected.
     *
     * @return true if connected
     */
    public boolean isConnected() {
        lock.lock();
        try {
            return connected;
        } finally {
            lock.unlock();
        }
    }

    private void setDataTypeMappingFlags() {
        dataTypeMappingFlags = 0;
        if (options.tinyInt1isBit) {
            dataTypeMappingFlags |= SelectResultSet.TINYINT1_IS_BIT;
        }
        if (options.yearIsDateType) {
            dataTypeMappingFlags |= SelectResultSet.YEAR_IS_DATE_TYPE;
        }
    }

    public long getServerThreadId() {
        return serverThreadId;
    }

    public int getDataTypeMappingFlags() {
        return dataTypeMappingFlags;
    }

    public boolean isExplicitClosed() {
        return explicitClosed;
    }

    public TimeZone getTimeZone() {
        return timeZone;
    }

    public Options getOptions() {
        return options;
    }

    public void setHasWarnings(boolean hasWarnings) {
        this.hasWarnings = hasWarnings;
    }

    public Results getActiveStreamingResult() {
        return activeStreamingResult;
    }

    public void setActiveStreamingResult(Results activeStreamingResult) {
        this.activeStreamingResult = activeStreamingResult;
    }

    /**
     * Remove exception result and since totally fetched, set fetch size to 0.
     */
    public void removeActiveStreamingResult() {
        if (this.activeStreamingResult != null) {
            this.activeStreamingResult.removeFetchSize();
            this.activeStreamingResult = null;
        }
    }

    @Override
    public ReentrantLock getLock() {
        return lock;
    }

    @Override
    public boolean hasMoreResults() {
        return (serverStatus & ServerStatus.MORE_RESULTS_EXISTS) != 0;
    }

    public ServerPrepareStatementCache prepareStatementCache() {
        return serverPrepareStatementCache;
    }

    public abstract void executeQuery(final String sql) throws SQLException;

    /**
     * Change Socket TcpNoDelay option.
     *
     * @param setTcpNoDelay value to set.
     */
    public void changeSocketTcpNoDelay(boolean setTcpNoDelay) {
        try {
            socket.setTcpNoDelay(setTcpNoDelay);
        } catch (SocketException socketException) {
            //eat exception
        }
    }

    public void changeSocketSoTimeout(int setSoTimeout) throws SocketException {
        socket.setSoTimeout(setSoTimeout);
    }

    public boolean isServerMariaDb() {
        return serverMariaDb;
    }

    public PacketInputStream getReader() {
        return reader;
    }

    public PacketOutputStream getWriter() {
        return writer;
    }

    public boolean isEofDeprecated() {
        return eofDeprecated;
    }

    public boolean sessionStateAware() {
        return (serverCapabilities & MariaDbServerCapabilities.CLIENT_SESSION_TRACK) != 0;
    }

    public String getTraces() {
        if (options.enablePacketDebug) return traceCache.printStack();
        return "";
    }
}
