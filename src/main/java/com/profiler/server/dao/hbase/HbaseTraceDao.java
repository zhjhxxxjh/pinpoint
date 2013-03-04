package com.profiler.server.dao.hbase;

import com.profiler.common.bo.AnnotationBo;
import com.profiler.common.bo.SpanBo;
import com.profiler.common.bo.SpanEvent;
import com.profiler.common.dto.thrift.Annotation;
import com.profiler.common.dto.thrift.Span;
import com.profiler.common.dto.thrift.SpanChunk;
import com.profiler.common.hbase.HbaseOperations2;
import com.profiler.common.buffer.Buffer;
import com.profiler.common.util.BytesUtils;
import com.profiler.common.buffer.FixedBuffer;
import com.profiler.common.util.SpanUtils;
import com.profiler.server.dao.TracesDao;
import com.profiler.server.util.AcceptedTime;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.util.Bytes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;

import static com.profiler.common.hbase.HBaseTables.*;

public class HbaseTraceDao implements TracesDao {

    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    @Autowired
    private HbaseOperations2 hbaseTemplate;

    @Override
    public void insert(final String applicationName, final Span span) {

        SpanBo spanBo = new SpanBo(span);

        Put put = new Put(SpanUtils.getTraceId(span));

        byte[] spanValue = spanBo.writeValue();
        // TODO columName이 중복일 경우를 확인가능하면 span id 중복 발급을 알수 있음.
        byte[] spanId = Bytes.toBytes(spanBo.getSpanId());
        long acceptedTime = AcceptedTime.getAcceptedTime();
        put.add(TRACES_CF_SPAN, spanId, acceptedTime, spanValue);

        List<Annotation> annotations = span.getAnnotations();
        if (annotations != null && annotations.size() != 0) {
            byte[] bytes = writeBuffer(annotations);
            put.add(TRACES_CF_ANNOTATION, spanId, bytes);
        }

        addNestedSpanEvent(put, span);

        hbaseTemplate.put(TRACES, put);

    }

    private void addNestedSpanEvent(Put put, Span span) {
        List<com.profiler.common.dto.thrift.SpanEvent> spanEventBoList = span.getSpanEventList();
        if (spanEventBoList == null || spanEventBoList.size() == 0) {
            return;
        }
        long acceptedTime = AcceptedTime.getAcceptedTime();
        for (com.profiler.common.dto.thrift.SpanEvent spanEvent : spanEventBoList) {
            SpanEvent spanEventBo = new SpanEvent(span, spanEvent);
            byte[] rowId = BytesUtils.add(spanEventBo.getSpanId(), spanEventBo.getSequence());
            byte[] value = spanEventBo.writeValue();
            put.add(TRACES_CF_TERMINALSPAN, rowId, acceptedTime, value);
        }
    }


    @Override
    public void insertEvent(final String applicationName, final com.profiler.common.dto.thrift.SpanEvent spanEvent) {
        SpanEvent spanEventBo = new SpanEvent(spanEvent);
        byte[] value = spanEventBo.writeValue();
        // TODO 서버 시간으로 변경해야 될듯 함. time이 생략...
        Put put = new Put(SpanUtils.getTraceId(spanEvent));

        byte[] rowId = BytesUtils.add(spanEventBo.getSpanId(), spanEventBo.getSequence());
        put.add(TRACES_CF_TERMINALSPAN, rowId, value);

        hbaseTemplate.put(TRACES, put);
    }

    @Override
    public void insertSpanChunk(String applicationName, SpanChunk spanChunk) {
        Put put = new Put(SpanUtils.getTraceId(spanChunk));

        long acceptedTime = AcceptedTime.getAcceptedTime();
        List<com.profiler.common.dto.thrift.SpanEvent> spanEventBoList = spanChunk.getSpanEventList();
        for (com.profiler.common.dto.thrift.SpanEvent spanEvent : spanEventBoList) {
            SpanEvent spanEventBo = new SpanEvent(spanChunk, spanEvent);

            byte[] value = spanEventBo.writeValue();
            byte[] rowId = BytesUtils.add(spanEventBo.getSpanId(), spanEventBo.getSequence());

            put.add(TRACES_CF_TERMINALSPAN, rowId, acceptedTime, value);
        }
        hbaseTemplate.put(TRACES, put);

    }

    private byte[] writeBuffer(List<Annotation> annotations) {
        int size = 0;
        List<AnnotationBo> boList = new ArrayList<AnnotationBo>(annotations.size());
        for (Annotation ano : annotations) {
            AnnotationBo annotationBo = new AnnotationBo(ano);
            size += annotationBo.getBufferSize();
            boList.add(annotationBo);
        }
        // size
        size += 4;
        Buffer buffer = new FixedBuffer(size);
        buffer.put(boList.size());
        for (AnnotationBo bo : boList) {
            bo.writeValue(buffer);
        }
        return buffer.getBuffer();
    }
}
