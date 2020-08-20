package com.uber.rss.handlers;

import com.uber.rss.messages.BaseMessage;
import com.uber.rss.messages.MessageConstants;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HandlerUtil {
    private static final Logger logger = LoggerFactory.getLogger(HandlerUtil.class);

    public static ChannelFuture writeResponseStatus(ChannelHandlerContext ctx, byte responseStatus) {
        ByteBuf responseMsgBuf = ctx.alloc().buffer(1);
        responseMsgBuf.writeByte(responseStatus);
        return ctx.writeAndFlush(responseMsgBuf);
    }

    // status and message specified as separate objects
    public static ChannelFuture writeResponseMsg(ChannelHandlerContext ctx, byte responseStatus, BaseMessage msg) {
        return writeResponseMsg(ctx, responseStatus, msg, false);
    }

    // status and message specified as separate objects
    public static ChannelFuture writeResponseMsg(ChannelHandlerContext ctx, byte responseStatus, BaseMessage msg, boolean doWriteType) {
        ByteBuf serializedMsgBuf = ctx.alloc().buffer(1000);
        try {
            // need to serialize msg to get its length
            msg.serialize(serializedMsgBuf);
            ByteBuf responseMsgBuf = ctx.alloc().buffer(1000);
            try {
                responseMsgBuf.writeByte(responseStatus);
                if (doWriteType) {
                    responseMsgBuf.writeInt(msg.getMessageType());
                }
                responseMsgBuf.writeInt(serializedMsgBuf.readableBytes());
                responseMsgBuf.writeBytes(serializedMsgBuf);
                return ctx.writeAndFlush(responseMsgBuf);
            } catch (Throwable ex) {
                logger.warn("Caught exception, releasing ByteBuf", ex);
                responseMsgBuf.release();
                throw ex;
            }
        } finally {
            serializedMsgBuf.release();
        }
    }

    // status and message specified as a combined object
    public static <T extends BaseMessage> ChannelFuture writeResponseMsg(ChannelHandlerContext ctx, ResponseStatusAndMessage<T> statusAndMessage) {
        return writeResponseMsg(ctx, statusAndMessage.getStatus(), statusAndMessage.getMessage());
    }

    // status OK, only message specified
    public static ChannelFuture writeResponseMsg(ChannelHandlerContext ctx, BaseMessage msg) {
        return writeResponseMsg(ctx, MessageConstants.RESPONSE_STATUS_OK, msg);
    }
}
