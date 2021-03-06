package de.bwaldvogel.mongo.wire;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.group.ChannelGroup;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.bson.BSONObject;
import org.bson.BasicBSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.bwaldvogel.mongo.MongoBackend;
import de.bwaldvogel.mongo.backend.Utils;
import de.bwaldvogel.mongo.exception.MongoServerError;
import de.bwaldvogel.mongo.exception.MongoServerException;
import de.bwaldvogel.mongo.exception.MongoSilentServerException;
import de.bwaldvogel.mongo.exception.NoSuchCommandException;
import de.bwaldvogel.mongo.wire.message.ClientRequest;
import de.bwaldvogel.mongo.wire.message.MessageHeader;
import de.bwaldvogel.mongo.wire.message.MongoDelete;
import de.bwaldvogel.mongo.wire.message.MongoInsert;
import de.bwaldvogel.mongo.wire.message.MongoQuery;
import de.bwaldvogel.mongo.wire.message.MongoReply;
import de.bwaldvogel.mongo.wire.message.MongoUpdate;

public class MongoDatabaseHandler extends SimpleChannelInboundHandler<ClientRequest> {

    private static final Logger log = LoggerFactory.getLogger(MongoWireProtocolHandler.class);

    private final AtomicInteger idSequence = new AtomicInteger();
    private final MongoBackend mongoBackend;

    private final ChannelGroup channelGroup;
    private final long started;
    private final Date startDate;

    public MongoDatabaseHandler(MongoBackend mongoBackend, ChannelGroup channelGroup) {
        this.channelGroup = channelGroup;
        this.mongoBackend = mongoBackend;
        this.started = System.nanoTime();
        this.startDate = new Date();
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        channelGroup.add(ctx.channel());
        log.info("client {} connected", ctx.channel());
        super.channelActive(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        log.info("channel {} closed", ctx.channel());
        channelGroup.remove(ctx.channel());
        mongoBackend.handleClose(ctx.channel());
        super.channelInactive(ctx);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ClientRequest object) throws Exception {
        if (object instanceof MongoQuery) {
            ctx.channel().writeAndFlush(handleQuery(ctx.channel(), (MongoQuery) object));
        } else if (object instanceof MongoInsert) {
            MongoInsert insert = (MongoInsert) object;
            mongoBackend.handleInsert(insert);
        } else if (object instanceof MongoDelete) {
            MongoDelete delete = (MongoDelete) object;
            mongoBackend.handleDelete(delete);
        } else if (object instanceof MongoUpdate) {
            MongoUpdate update = (MongoUpdate) object;
            mongoBackend.handleUpdate(update);
        } else {
            throw new MongoServerException("unknown message: " + object);
        }
    }

    public Date getStartDate() {
        return startDate;
    }

    protected MongoReply handleQuery(Channel channel, MongoQuery query) {
        List<BSONObject> documents = new ArrayList<BSONObject>();
        MessageHeader header = new MessageHeader(idSequence.incrementAndGet(), query.getHeader().getRequestID());
        try {
            if (query.getCollectionName().startsWith("$cmd")) {
                documents.add(handleCommand(channel, query, documents));
            } else {
                for (BSONObject obj : mongoBackend.handleQuery(query)) {
                    documents.add(obj);
                }
            }
        } catch (NoSuchCommandException e) {
            log.error("unknown command: {}", query, e);
            BSONObject obj = new BasicBSONObject();
            obj.put("errmsg", "no such cmd: " + e.getCommand());
            obj.put("bad cmd", query.getQuery());
            obj.put("code", Integer.valueOf(e.getCode()));
            obj.put("ok", Integer.valueOf(0));
            documents.add(obj);
        } catch (MongoServerError e) {
            log.error("failed to handle query {}", query, e);
            BSONObject obj = new BasicBSONObject();
            obj.put("errmsg", e.getMessage());
            obj.put("code", Integer.valueOf(e.getCode()));
            obj.put("ok", Integer.valueOf(0));
            documents.add(obj);
        } catch (MongoSilentServerException e) {
            BSONObject obj = new BasicBSONObject();
            obj.put("errmsg", e.getMessage());
            obj.put("ok", Integer.valueOf(0));
            documents.add(obj);
        } catch (MongoServerException e) {
            log.error("failed to handle query {}", query, e);
            BSONObject obj = new BasicBSONObject();
            obj.put("errmsg", e.getMessage());
            obj.put("ok", Integer.valueOf(0));
            documents.add(obj);
        }

        return new MongoReply(header, documents);
    }

    protected BSONObject handleCommand(Channel channel, MongoQuery query, List<BSONObject> documents)
            throws MongoServerException {
        String collectionName = query.getCollectionName();
        if (collectionName.equals("$cmd.sys.inprog")) {
            Collection<BSONObject> currentOperations = mongoBackend.getCurrentOperations(query);
            return new BasicBSONObject("inprog", currentOperations);
        }

        if (collectionName.equals("$cmd")) {
            String command = query.getQuery().keySet().iterator().next();
            if (command.equals("serverStatus")) {
                return getServerStatus();
            } else {
                return mongoBackend.handleCommand(channel, query.getDatabaseName(), command, query.getQuery());
            }
        }

        throw new MongoServerException("unknown collection: " + collectionName);
    }

    private BSONObject getServerStatus() throws MongoServerException {
        BSONObject serverStatus = new BasicBSONObject();
        try {
            serverStatus.put("host", InetAddress.getLocalHost().getHostName());
        } catch (UnknownHostException e) {
            throw new MongoServerException("failed to get hostname", e);
        }
        serverStatus.put("version", Utils.join(mongoBackend.getVersion(), '.'));
        serverStatus.put("process", "java");
        serverStatus.put("pid", getProcessId());

        serverStatus.put("uptime", Integer.valueOf((int) TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started)));
        serverStatus.put("uptimeMillis", Long.valueOf(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)));
        serverStatus.put("localTime", new Date());

        BSONObject connections = new BasicBSONObject();
        connections.put("current", Integer.valueOf(channelGroup.size()));

        serverStatus.put("connections", connections);

        BSONObject cursors = new BasicBSONObject();
        cursors.put("totalOpen", Integer.valueOf(0)); // TODO

        serverStatus.put("cursors", cursors);

        Utils.markOkay(serverStatus);

        return serverStatus;
    }

    private Integer getProcessId() {
        String runtimeName = ManagementFactory.getRuntimeMXBean().getName();
        if (runtimeName.contains("@")) {
            return Integer.valueOf(runtimeName.substring(0, runtimeName.indexOf('@')));
        }
        return Integer.valueOf(0);
    }
}
