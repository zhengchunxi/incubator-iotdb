package cn.edu.tsinghua.iotdb.query.reader;

import cn.edu.tsinghua.iotdb.query.dataset.InsertDynamicData;
import cn.edu.tsinghua.tsfile.common.utils.Binary;
import cn.edu.tsinghua.tsfile.common.utils.Pair;
import cn.edu.tsinghua.tsfile.encoding.decoder.Decoder;
import cn.edu.tsinghua.tsfile.file.metadata.enums.TSDataType;
import cn.edu.tsinghua.tsfile.format.PageHeader;
import cn.edu.tsinghua.tsfile.timeseries.filter.definition.SingleSeriesFilterExpression;
import cn.edu.tsinghua.tsfile.timeseries.filter.visitorImpl.SingleValueVisitor;
import cn.edu.tsinghua.tsfile.timeseries.filter.visitorImpl.SingleValueVisitorFactory;
import cn.edu.tsinghua.tsfile.timeseries.read.query.DynamicOneColumnData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;


/**
 * This class is a complement of <code>ValueReaderProcessor</code>.
 * The main method in this class is <code>readOnePage</code>, it supplies a page level read logic.
 *
 */
public class ReaderUtils {

    private static final Logger logger = LoggerFactory.getLogger(ReaderUtils.class);

    /**
     * -1: no updateTrue data, no updateFalse data.
     * 0: updateTrue data < updateFalse data.
     * 1: updateFalse data < updateTrue data.
     *
     * @param updateTrueIdx  index of updateTrue DynamicOneColumn
     * @param updateFalseIdx index of updateFalse DynamicOneColumn
     * @param updateTrue     updateTrue DynamicOneColumn
     * @param updateFalse    updateFalse DynamicOneColumn
     * @return the mode
     */
    public static int getNextMode(int updateTrueIdx, int updateFalseIdx, DynamicOneColumnData updateTrue, DynamicOneColumnData updateFalse) {
        if (updateTrueIdx > updateTrue.timeLength - 2 && updateFalseIdx > updateFalse.timeLength - 2) {
            return -1;
        } else if (updateTrueIdx <= updateTrue.timeLength - 2 && updateFalseIdx > updateFalse.timeLength - 2) {
            return 0;
        } else if (updateTrueIdx > updateTrue.timeLength - 2 && updateFalseIdx <= updateFalse.timeLength - 2) {
            return 1;
        } else {
            long t0 = updateTrue.getTime(updateTrueIdx);
            long t1 = updateFalse.getTime(updateFalseIdx);
            return t0 < t1 ? 0 : 1;
        }
    }

    private static SingleValueVisitor<?> getSingleValueVisitorByDataType(TSDataType type, SingleSeriesFilterExpression filter) {
        switch (type) {
            case INT32:
                return new SingleValueVisitor<Integer>(filter);
            case INT64:
                return new SingleValueVisitor<Long>(filter);
            case FLOAT:
                return new SingleValueVisitor<Float>(filter);
            case DOUBLE:
                return new SingleValueVisitor<Double>(filter);
            default:
                return SingleValueVisitorFactory.getSingleValueVisitor(type);
        }
    }

    /**
     * <p>
     * Read one page data,
     * this page data may be changed by overflow operation, so the overflow parameter is required.
     *
     * @param dataType the <code>DataType</code> of the read page
     * @param pageTimeValues the decompressed timestamps of this page
     * @param decoder the <code>Decoder</code> of current page
     * @param page Page data
     * @param res same as result data, we need pass it many times
     * @param timeFilter time filter
     * @param freqFilter frequency filter
     * @param valueFilter value filter
     * @param insertMemoryData the memory data(bufferwrite along with overflow)
     * @param update update operation array, update[0] means updateTrue data, update[1] means updateFalse data
     * @return DynamicOneColumnData of the read result
     * @throws IOException TsFile read error
     * @param idx the read index of timeValues
     */
    public static DynamicOneColumnData readOnePage(TSDataType dataType, long[] pageTimeValues,
                                                   Decoder decoder, InputStream page, DynamicOneColumnData res,
                                                   SingleSeriesFilterExpression timeFilter, SingleSeriesFilterExpression freqFilter, SingleSeriesFilterExpression valueFilter,
                                                   InsertDynamicData insertMemoryData, DynamicOneColumnData[] update, int[] idx) throws IOException {
        // This method is only used for aggregation function.

        // calculate current mode
        int mode = getNextMode(idx[0], idx[1], update[0], update[1]);

        try {
            SingleValueVisitor<?> timeVisitor = null;
            if (timeFilter != null) {
                timeVisitor = getSingleValueVisitorByDataType(TSDataType.INT64, timeFilter);
            }
            SingleValueVisitor<?> valueVisitor = null;
            if (valueFilter != null) {
                valueVisitor = getSingleValueVisitorByDataType(dataType, valueFilter);
            }

            int timeIdx = 0;
            switch (dataType) {
                case INT32:
                    while (decoder.hasNext(page)) {
                        // put insert points that less than or equals to current
                        // Timestamp in page.
                        while (insertMemoryData.hasInsertData() && timeIdx < pageTimeValues.length
                                && insertMemoryData.getCurrentMinTime() <= pageTimeValues[timeIdx]) {
                            res.putTime(insertMemoryData.getCurrentMinTime());
                            res.putInt(insertMemoryData.getCurrentIntValue());
                            res.insertTrueIndex++;

                            if (insertMemoryData.getCurrentMinTime() == pageTimeValues[timeIdx]) {
                                insertMemoryData.removeCurrentValue();
                                timeIdx++;
                                decoder.readInt(page);
                                if (!decoder.hasNext(page)) {
                                    break;
                                }
                            } else {
                                insertMemoryData.removeCurrentValue();
                            }
                        }

                        if (!decoder.hasNext(page)) {
                            break;
                        }
                        int v = decoder.readInt(page);
                        if (mode == -1) {
                            if ((valueFilter == null && timeFilter == null)
                                    || (valueFilter != null && timeFilter == null && valueVisitor.verify(v))
                                    || (valueFilter == null && timeFilter != null && timeVisitor.verify(pageTimeValues[timeIdx]))
                                    || (valueFilter != null && timeFilter != null && valueVisitor.verify(v) && timeVisitor.verify(pageTimeValues[timeIdx]))) {
                                res.putInt(v);
                                res.putTime(pageTimeValues[timeIdx]);
                            }
                            timeIdx++;
                        }

                        if (mode == 0) {
                            if (update[0].getTime(idx[0]) <= pageTimeValues[timeIdx]
                                    && pageTimeValues[timeIdx] <= update[0].getTime(idx[0] + 1)) {
                                // update the value
                                if (timeFilter == null
                                        || timeVisitor.verify(pageTimeValues[timeIdx])) {
                                    res.putInt(update[0].getInt(idx[0] / 2));
                                    res.putTime(pageTimeValues[timeIdx]);
                                }
                            } else if ((valueFilter == null && timeFilter == null)
                                    || (valueFilter != null && timeFilter == null && valueVisitor.verify(v))
                                    || (valueFilter == null && timeFilter != null && timeVisitor.verify(pageTimeValues[timeIdx]))
                                    || (valueFilter != null && timeFilter != null && valueVisitor.verify(v) && timeVisitor.verify(pageTimeValues[timeIdx]))) {
                                res.putInt(v);
                                res.putTime(pageTimeValues[timeIdx]);
                            }
                            timeIdx++;
                        }

                        if (mode == 1) {
                            if (update[1].getTime(idx[1]) <= pageTimeValues[timeIdx]
                                    && pageTimeValues[timeIdx] <= update[1].getTime(idx[1] + 1)) {
                                // do nothing
                            } else if ((valueFilter == null && timeFilter == null)
                                    || (valueFilter != null && timeFilter == null && valueVisitor.verify(v))
                                    || (valueFilter == null && timeFilter != null && timeVisitor.verify(pageTimeValues[timeIdx]))
                                    || (valueFilter != null && timeFilter != null && valueVisitor.verify(v) && timeVisitor.verify(pageTimeValues[timeIdx]))) {
                                res.putInt(v);
                                res.putTime(pageTimeValues[timeIdx]);
                            }
                            timeIdx++;
                        }

                        // Set the interval to next position that current time
                        // in page maybe be included.
                        while (mode != -1 && timeIdx < pageTimeValues.length
                                && pageTimeValues[timeIdx] > update[mode].getTime(idx[mode] + 1)) {
                            idx[mode] += 2;
                            mode = getNextMode(idx[0], idx[1], update[0], update[1]);
                        }
                    }
                    break;
                case BOOLEAN:
                    while (decoder.hasNext(page)) {
                        // put insert points
                        while (insertMemoryData.curIdx < insertMemoryData.valueLength && timeIdx < pageTimeValues.length
                                && insertMemoryData.getTime(insertMemoryData.curIdx) <= pageTimeValues[timeIdx]) {
                            res.putTime(insertMemoryData.getTime(insertMemoryData.curIdx));
                            res.putBoolean(insertMemoryData.getBoolean(insertMemoryData.curIdx));
                            insertMemoryData.curIdx++;
                            res.insertTrueIndex++;
                            // if equal, take value from insertTrue and skip one
                            // value from page
                            if (insertMemoryData.getTime(insertMemoryData.curIdx - 1) == pageTimeValues[timeIdx]) {
                                timeIdx++;
                                decoder.readBoolean(page);
                                if (!decoder.hasNext(page)) {
                                    break;
                                }
                            }
                        }

                        if (mode == -1) {
                            boolean v = decoder.readBoolean(page);
                            if ((valueFilter == null && timeFilter == null)
                                    || (valueFilter != null && timeFilter == null && valueVisitor.satisfyObject(v, valueFilter))
                                    || (valueFilter == null && timeFilter != null && timeVisitor.verify(pageTimeValues[timeIdx]))
                                    || (valueFilter != null && timeFilter != null && valueVisitor.satisfyObject(v, valueFilter) && timeVisitor.verify(pageTimeValues[timeIdx]))) {
                                res.putBoolean(v);
                                res.putTime(pageTimeValues[timeIdx]);
                            }
                            timeIdx++;
                        }

                        if (mode == 0) {
                            boolean v = decoder.readBoolean(page);
                            if (update[0].getTime(idx[0]) <= pageTimeValues[timeIdx]
                                    && pageTimeValues[timeIdx] <= update[0].getTime(idx[0] + 1)) {
                                // update the value
                                if (timeFilter == null
                                        || timeVisitor.verify(pageTimeValues[timeIdx])) {
                                    res.putBoolean(update[0].getBoolean(idx[0] / 2));
                                    res.putTime(pageTimeValues[timeIdx]);
                                }
                            } else if ((valueFilter == null && timeFilter == null)
                                    || (valueFilter != null && timeFilter == null && valueVisitor.satisfyObject(v, valueFilter))
                                    || (valueFilter == null && timeFilter != null && timeVisitor.verify(pageTimeValues[timeIdx]))
                                    || (valueFilter != null && timeFilter != null && valueVisitor.satisfyObject(v, valueFilter) && timeVisitor.verify(pageTimeValues[timeIdx]))) {
                                res.putBoolean(v);
                                res.putTime(pageTimeValues[timeIdx]);
                            }
                            timeIdx++;
                        }

                        if (mode == 1) {
                            boolean v = decoder.readBoolean(page);
                            if (update[1].getTime(idx[1]) <= pageTimeValues[timeIdx]
                                    && pageTimeValues[timeIdx] <= update[1].getTime(idx[1] + 1)) {
                                // do nothing
                            } else if ((valueFilter == null && timeFilter == null)
                                    || (valueFilter != null && timeFilter == null && valueVisitor.satisfyObject(v, valueFilter))
                                    || (valueFilter == null && timeFilter != null && timeVisitor.verify(pageTimeValues[timeIdx]))
                                    || (valueFilter != null && timeFilter != null && valueVisitor.satisfyObject(v, valueFilter) && timeVisitor.verify(pageTimeValues[timeIdx]))) {
                                res.putBoolean(v);
                                res.putTime(pageTimeValues[timeIdx]);
                            }
                            timeIdx++;
                        }

                        while (mode != -1 && timeIdx < pageTimeValues.length
                                && pageTimeValues[timeIdx] > update[mode].getTime(idx[mode] + 1)) {
                            idx[mode] += 2;
                            mode = getNextMode(idx[0], idx[1], update[0], update[1]);
                        }
                    }
                    break;
                case INT64:
                    while (decoder.hasNext(page)) {
                        // put insert points
                        while (insertMemoryData.curIdx < insertMemoryData.valueLength && timeIdx < pageTimeValues.length
                                && insertMemoryData.getTime(insertMemoryData.curIdx) <= pageTimeValues[timeIdx]) {
                            res.putTime(insertMemoryData.getTime(insertMemoryData.curIdx));
                            res.putLong(insertMemoryData.getLong(insertMemoryData.curIdx));
                            insertMemoryData.curIdx++;
                            res.insertTrueIndex++;
                            // if equal, take value from insertTrue and skip one
                            // value from page
                            if (insertMemoryData.getTime(insertMemoryData.curIdx - 1) == pageTimeValues[timeIdx]) {
                                timeIdx++;
                                decoder.readLong(page);
                                if (!decoder.hasNext(page)) {
                                    break;
                                }
                            }
                        }
                        if (!decoder.hasNext(page)) {
                            break;
                        }
                        long v = decoder.readLong(page);
                        if (mode == -1) {
                            if ((valueFilter == null && timeFilter == null)
                                    || (valueFilter != null && timeFilter == null && valueVisitor.verify(v))
                                    || (valueFilter == null && timeFilter != null && timeVisitor.verify(pageTimeValues[timeIdx]))
                                    || (valueFilter != null && timeFilter != null && valueVisitor.verify(v) && timeVisitor.verify(pageTimeValues[timeIdx]))) {
                                res.putLong(v);
                                res.putTime(pageTimeValues[timeIdx]);
                            }
                            timeIdx++;
                        }

                        if (mode == 0) {
                            if (update[0].getTime(idx[0]) <= pageTimeValues[timeIdx]
                                    && pageTimeValues[timeIdx] <= update[0].getTime(idx[0] + 1)) {
                                // TODO update the value, need to discuss the logic with gf?
                                if (timeFilter == null
                                        || timeVisitor.verify(pageTimeValues[timeIdx])) {
                                    res.putLong(update[0].getLong(idx[0] / 2));
                                    res.putTime(pageTimeValues[timeIdx]);
                                }
                            } else if ((valueFilter == null && timeFilter == null)
                                    || (valueFilter != null && timeFilter == null && valueVisitor.verify(v))
                                    || (valueFilter == null && timeFilter != null && timeVisitor.verify(pageTimeValues[timeIdx]))
                                    || (valueFilter != null && timeFilter != null && valueVisitor.verify(v) && timeVisitor.verify(pageTimeValues[timeIdx]))) {
                                res.putLong(v);
                                res.putTime(pageTimeValues[timeIdx]);
                            }
                            timeIdx++;
                        }

                        if (mode == 1) {
                            if (update[1].getTime(idx[1]) <= pageTimeValues[timeIdx]
                                    && pageTimeValues[timeIdx] <= update[1].getTime(idx[1] + 1)) {
                                // do nothing
                            } else if ((valueFilter == null && timeFilter == null)
                                    || (valueFilter != null && timeFilter == null && valueVisitor.verify(v))
                                    || (valueFilter == null && timeFilter != null && timeVisitor.verify(pageTimeValues[timeIdx]))
                                    || (valueFilter != null && timeFilter != null && valueVisitor.verify(v) && timeVisitor.verify(pageTimeValues[timeIdx]))) {
                                res.putLong(v);
                                res.putTime(pageTimeValues[timeIdx]);
                            }
                            timeIdx++;
                        }

                        while (mode != -1 && timeIdx < pageTimeValues.length
                                && pageTimeValues[timeIdx] > update[mode].getTime(idx[mode] + 1)) {
                            idx[mode] += 2;
                            mode = getNextMode(idx[0], idx[1], update[0], update[1]);
                        }
                    }
                    break;
                case FLOAT:
                    while (decoder.hasNext(page)) {
                        // put insert points
                        while (insertMemoryData.curIdx < insertMemoryData.valueLength && timeIdx < pageTimeValues.length
                                && insertMemoryData.getTime(insertMemoryData.curIdx) <= pageTimeValues[timeIdx]) {
                            res.putTime(insertMemoryData.getTime(insertMemoryData.curIdx));
                            res.putFloat(insertMemoryData.getFloat(insertMemoryData.curIdx));
                            insertMemoryData.curIdx++;
                            res.insertTrueIndex++;
                            // if equal, take value from insertTrue and skip one
                            // value from page
                            if (insertMemoryData.getTime(insertMemoryData.curIdx - 1) == pageTimeValues[timeIdx]) {
                                timeIdx++;
                                decoder.readFloat(page);
                                if (!decoder.hasNext(page)) {
                                    break;
                                }
                            }
                        }
                        if (!decoder.hasNext(page)) {
                            break;
                        }
                        float v = decoder.readFloat(page);
                        if (mode == -1) {
                            if ((valueFilter == null && timeFilter == null)
                                    || (valueFilter != null && timeFilter == null && valueVisitor.verify(v))
                                    || (valueFilter == null && timeFilter != null && timeVisitor.verify(pageTimeValues[timeIdx]))
                                    || (valueFilter != null && timeFilter != null && valueVisitor.verify(v)
                                    && timeVisitor.verify(pageTimeValues[timeIdx]))) {
                                res.putFloat(v);
                                res.putTime(pageTimeValues[timeIdx]);
                            }
                            timeIdx++;
                        }

                        if (mode == 0) {
                            if (update[0].getTime(idx[0]) <= pageTimeValues[timeIdx]
                                    && pageTimeValues[timeIdx] <= update[0].getTime(idx[0] + 1)) {
                                // update the value
                                if (timeFilter == null
                                        || timeVisitor.verify(pageTimeValues[timeIdx])) {
                                    res.putFloat(update[0].getFloat(idx[0] / 2));
                                    res.putTime(pageTimeValues[timeIdx]);
                                }
                            } else if ((valueFilter == null && timeFilter == null)
                                    || (valueFilter != null && timeFilter == null && valueVisitor.verify(v))
                                    || (valueFilter == null && timeFilter != null && timeVisitor.verify(pageTimeValues[timeIdx]))
                                    || (valueFilter != null && timeFilter != null && valueVisitor.verify(v) && timeVisitor.verify(pageTimeValues[timeIdx]))) {
                                res.putFloat(v);
                                res.putTime(pageTimeValues[timeIdx]);
                            }
                            timeIdx++;
                        }

                        if (mode == 1) {
                            if (update[1].getTime(idx[1]) <= pageTimeValues[timeIdx]
                                    && pageTimeValues[timeIdx] <= update[1].getTime(idx[1] + 1)) {
                                // do nothing
                            } else if ((valueFilter == null && timeFilter == null)
                                    || (valueFilter != null && timeFilter == null && valueVisitor.verify(v))
                                    || (valueFilter == null && timeFilter != null && timeVisitor.verify(pageTimeValues[timeIdx]))
                                    || (valueFilter != null && timeFilter != null && valueVisitor.verify(v) && timeVisitor.verify(pageTimeValues[timeIdx]))) {
                                res.putFloat(v);
                                res.putTime(pageTimeValues[timeIdx]);
                            }
                            timeIdx++;
                        }

                        while (mode != -1 && timeIdx < pageTimeValues.length
                                && pageTimeValues[timeIdx] > update[mode].getTime(idx[mode] + 1)) {
                            idx[mode] += 2;
                            mode = getNextMode(idx[0], idx[1], update[0], update[1]);
                        }
                    }
                    break;
                case DOUBLE:
                    while (decoder.hasNext(page)) {
                        // put insert points
                        while (insertMemoryData.curIdx < insertMemoryData.valueLength && timeIdx < pageTimeValues.length
                                && insertMemoryData.getTime(insertMemoryData.curIdx) <= pageTimeValues[timeIdx]) {
                            res.putTime(insertMemoryData.getTime(insertMemoryData.curIdx));
                            res.putDouble(insertMemoryData.getDouble(insertMemoryData.curIdx));
                            insertMemoryData.curIdx++;
                            res.insertTrueIndex++;
                            // if equal, take value from insertTrue and skip one
                            // value from page
                            if (insertMemoryData.getTime(insertMemoryData.curIdx - 1) == pageTimeValues[timeIdx]) {
                                timeIdx++;
                                decoder.readDouble(page);
                                if (!decoder.hasNext(page)) {
                                    break;
                                }
                            }
                        }
                        if (!decoder.hasNext(page)) {
                            break;
                        }
                        double v = decoder.readDouble(page);
                        if (mode == -1) {
                            if ((valueFilter == null && timeFilter == null)
                                    || (valueFilter != null && timeFilter == null && valueVisitor.verify(v))
                                    || (valueFilter == null && timeFilter != null && timeVisitor.verify(pageTimeValues[timeIdx]))
                                    || (valueFilter != null && timeFilter != null && valueVisitor.verify(v) && timeVisitor.verify(pageTimeValues[timeIdx]))) {
                                res.putDouble(v);
                                res.putTime(pageTimeValues[timeIdx]);
                            }
                            timeIdx++;
                        }

                        if (mode == 0) {
                            if (update[0].getTime(idx[0]) <= pageTimeValues[timeIdx]
                                    && pageTimeValues[timeIdx] <= update[0].getTime(idx[0] + 1)) {
                                // update the value
                                if (timeFilter == null
                                        || timeVisitor.verify(pageTimeValues[timeIdx])) {
                                    res.putDouble(update[0].getDouble(idx[0] / 2));
                                    res.putTime(pageTimeValues[timeIdx]);
                                }
                            } else if ((valueFilter == null && timeFilter == null)
                                    || (valueFilter != null && timeFilter == null && valueVisitor.verify(v))
                                    || (valueFilter == null && timeFilter != null && timeVisitor.verify(pageTimeValues[timeIdx]))
                                    || (valueFilter != null && timeFilter != null && valueVisitor.verify(v) && timeVisitor.verify(pageTimeValues[timeIdx]))) {
                                res.putDouble(v);
                                res.putTime(pageTimeValues[timeIdx]);
                            }
                            timeIdx++;
                        }

                        if (mode == 1) {
                            if (update[1].getTime(idx[1]) <= pageTimeValues[timeIdx]
                                    && pageTimeValues[timeIdx] <= update[1].getTime(idx[1] + 1)) {
                                // do nothing
                            } else if ((valueFilter == null && timeFilter == null)
                                    || (valueFilter != null && timeFilter == null && valueVisitor.verify(v))
                                    || (valueFilter == null && timeFilter != null && timeVisitor.verify(pageTimeValues[timeIdx]))
                                    || (valueFilter != null && timeFilter != null && valueVisitor.verify(v) && timeVisitor.verify(pageTimeValues[timeIdx]))) {
                                res.putDouble(v);
                                res.putTime(pageTimeValues[timeIdx]);
                            }
                            timeIdx++;
                        }

                        while (mode != -1 && timeIdx < pageTimeValues.length
                                && pageTimeValues[timeIdx] > update[mode].getTime(idx[mode] + 1)) {
                            idx[mode] += 2;
                            mode = getNextMode(idx[0], idx[1], update[0], update[1]);
                        }
                    }
                    break;
                case TEXT:
                    while (decoder.hasNext(page)) {
                        // put insert points
                        while (insertMemoryData.curIdx < insertMemoryData.valueLength && timeIdx < pageTimeValues.length
                                && insertMemoryData.getTime(insertMemoryData.curIdx) <= pageTimeValues[timeIdx]) {
                            res.putTime(insertMemoryData.getTime(insertMemoryData.curIdx));
                            res.putBinary(insertMemoryData.getBinary(insertMemoryData.curIdx));
                            insertMemoryData.curIdx++;
                            res.insertTrueIndex++;
                            // if equal, take value from insertTrue and skip one
                            // value from page
                            if (insertMemoryData.getTime(insertMemoryData.curIdx - 1) == pageTimeValues[timeIdx]) {
                                timeIdx++;
                                decoder.readBinary(page);
                                if (!decoder.hasNext(page)) {
                                    break;
                                }
                            }
                        }
                        if (!decoder.hasNext(page)) {
                            break;
                        }
                        Binary v = decoder.readBinary(page);
                        if (mode == -1) {
                            if ((valueFilter == null && timeFilter == null)
                                    || (valueFilter != null && timeFilter == null && valueVisitor.satisfyObject(v, valueFilter))
                                    || (valueFilter == null && timeFilter != null && timeVisitor.verify(pageTimeValues[timeIdx]))
                                    || (valueFilter != null && timeFilter != null && valueVisitor.satisfyObject(v, valueFilter) && timeVisitor.verify(pageTimeValues[timeIdx]))) {
                                res.putBinary(v);
                                res.putTime(pageTimeValues[timeIdx]);
                            }
                            timeIdx++;
                        }

                        if (mode == 0) {
                            if (update[0].getTime(idx[0]) <= pageTimeValues[timeIdx]
                                    && pageTimeValues[timeIdx] <= update[0].getTime(idx[0] + 1)) {
                                // update the value
                                if (timeFilter == null
                                        || timeVisitor.verify(pageTimeValues[timeIdx])) {
                                    res.putBinary(update[0].getBinary(idx[0] / 2));
                                    res.putTime(pageTimeValues[timeIdx]);
                                }
                            } else if ((valueFilter == null && timeFilter == null)
                                    || (valueFilter != null && timeFilter == null && valueVisitor.satisfyObject(v, valueFilter))
                                    || (valueFilter == null && timeFilter != null && timeVisitor.verify(pageTimeValues[timeIdx]))
                                    || (valueFilter != null && timeFilter != null && valueVisitor.satisfyObject(v, valueFilter) && timeVisitor.verify(pageTimeValues[timeIdx]))) {
                                res.putBinary(v);
                                res.putTime(pageTimeValues[timeIdx]);
                            }
                            timeIdx++;
                        }

                        if (mode == 1) {
                            if (update[1].getTime(idx[1]) <= pageTimeValues[timeIdx]
                                    && pageTimeValues[timeIdx] <= update[1].getTime(idx[1] + 1)) {
                                // do nothing
                                logger.error("never reach here");
                            } else if ((valueFilter == null && timeFilter == null)
                                    || (valueFilter != null && timeFilter == null && valueVisitor.satisfyObject(v, valueFilter))
                                    || (valueFilter == null && timeFilter != null && timeVisitor.verify(pageTimeValues[timeIdx]))
                                    || (valueFilter != null && timeFilter != null && valueVisitor.satisfyObject(v, valueFilter) && timeVisitor.verify(pageTimeValues[timeIdx]))) {
                                res.putBinary(v);
                                res.putTime(pageTimeValues[timeIdx]);
                            }
                            timeIdx++;
                        }

                        while (mode != -1 && timeIdx < pageTimeValues.length
                                && pageTimeValues[timeIdx] > update[mode].getTime(idx[mode] + 1)) {
                            idx[mode] += 2;
                            mode = getNextMode(idx[0], idx[1], update[0], update[1]);
                        }
                    }
                    break;
                default:
                    throw new IOException("Data type not support : " + dataType);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        // Don't forget to update the curIdx in updateTrue and updateFalse
        update[0].curIdx = idx[0];
        update[1].curIdx = idx[1];
        return res;
    }

    /**
     * <p>
     * An aggregation method implementation for the DataPage aspect.
     * This method is only used for aggregation function.
     *
     * @param dataType DataPage data type
     * @param pageTimeStamps the timestamps of current DataPage
     * @param decoder the decoder of DataPage
     * @param page the DataPage need to be aggregated
     * @param timeFilter time filter
     * @param freqFilter frequency filter
     * @param commonTimestamps the timestamps which aggregation must satisfy
     * @param commonTimestampsIndex the read time index of timestamps which aggregation must satisfy
     * @param insertMemoryData bufferwrite memory insert data with overflow operation
     * @param update an array of overflow update info, update[0] represents updateTrue,
     *               while update[1] represents updateFalse
     * @param updateIdx an array of the index of overflow update info, update[0] represents the index of
     *                  updateTrue, while update[1] represents updateFalse
     * @return left represents the data of DataPage which satisfies the restrict condition,
     *         right represents the read time index of commonTimestamps
     * @throws IOException TsFile read error
     */
    public static Pair<DynamicOneColumnData, Integer> readOnePage(TSDataType dataType, long[] pageTimeStamps,
                Decoder decoder, InputStream page,
                SingleSeriesFilterExpression timeFilter, SingleSeriesFilterExpression freqFilter,
                List<Long> commonTimestamps, int commonTimestampsIndex,
                InsertDynamicData insertMemoryData, DynamicOneColumnData[] update, int[] updateIdx) throws IOException {

        //TODO optimize the logic, we could read the page data firstly, the make filter about the data, it's easy to check

        DynamicOneColumnData aggregatePathQueryResult = new DynamicOneColumnData(dataType, true);
        int pageTimeIndex = 0;

        // calculate current mode
        int mode = getNextMode(updateIdx[0], updateIdx[1], update[0], update[1]);

        try {
            SingleValueVisitor<?> timeVisitor = null;
            if (timeFilter != null) {
                timeVisitor = getSingleValueVisitorByDataType(TSDataType.INT64, timeFilter);
            }

            switch (dataType) {
                case INT32:
                    while (decoder.hasNext(page)) {
                        long commonTimestamp = commonTimestamps.get(commonTimestampsIndex);

                        while (insertMemoryData.hasInsertData() && pageTimeIndex < pageTimeStamps.length
                                && insertMemoryData.getCurrentMinTime() <= pageTimeStamps[pageTimeIndex]) {

                            if (insertMemoryData.getCurrentMinTime() == commonTimestamp) {
                                aggregatePathQueryResult.putTime(insertMemoryData.getCurrentMinTime());
                                aggregatePathQueryResult.putInt(insertMemoryData.getCurrentIntValue());
                                aggregatePathQueryResult.insertTrueIndex++;

                                // both insertMemory value and page value should be removed
                                if (insertMemoryData.getCurrentMinTime() == pageTimeStamps[pageTimeIndex]) {
                                    insertMemoryData.removeCurrentValue();
                                    pageTimeIndex++;
                                    decoder.readInt(page);
                                } else {
                                    insertMemoryData.removeCurrentValue();
                                }

                                commonTimestampsIndex += 1;
                                if (commonTimestampsIndex < commonTimestamps.size()) {
                                    commonTimestamp = commonTimestamps.get(commonTimestampsIndex);
                                } else {
                                    break;
                                }

                            } else if (insertMemoryData.getCurrentMinTime() < commonTimestamp){
                                insertMemoryData.removeCurrentValue();
                            } else {
                                commonTimestampsIndex += 1;
                                if (commonTimestampsIndex < commonTimestamps.size()) {
                                    commonTimestamp = commonTimestamps.get(commonTimestampsIndex);
                                } else {
                                    break;
                                }
                            }
                        }

                        if (!decoder.hasNext(page) || pageTimeIndex >= pageTimeStamps.length) {
                            break;
                        }
                        if (commonTimestampsIndex >= commonTimestamps.size()) {
                            break;
                        }

                        // compare with commonTimestamps firstly
                        // then compare with time filter
                        // lastly compare with update operation

                        // no updateTrue, no updateFalse
                        if (mode == -1) {
                            if (pageTimeStamps[pageTimeIndex] == commonTimestamps.get(commonTimestampsIndex)) {
                                if ((timeFilter == null || timeVisitor.verify(pageTimeStamps[pageTimeIndex]))) {
                                    aggregatePathQueryResult.putTime(pageTimeStamps[pageTimeIndex]);
                                    int v = decoder.readInt(page);
                                    aggregatePathQueryResult.putInt(v);
                                    aggregatePathQueryResult.insertTrueIndex++;
                                }

                                // no matter if time is satisfied with time filter, the two index will plus
                                commonTimestampsIndex += 1;
                                pageTimeIndex += 1;
                            } else if (pageTimeStamps[pageTimeIndex] < commonTimestamps.get(commonTimestampsIndex)) {
                                decoder.readInt(page);
                                pageTimeIndex += 1;
                            } else {
                                commonTimestampsIndex += 1;
                            }
                        }
                        // updateTrue data < updateFalse data.
                        else if (mode == 0) {
                            if (pageTimeStamps[pageTimeIndex] == commonTimestamps.get(commonTimestampsIndex)) {
                                int v = decoder.readInt(page);

                                if (timeFilter == null || timeVisitor.verify(pageTimeStamps[pageTimeIndex])) {
                                    // if updateTrue changes the original value
                                    if (update[0].getTime(updateIdx[0]) <= pageTimeStamps[pageTimeIndex]
                                            && pageTimeStamps[pageTimeIndex] <= update[0].getTime(updateIdx[0] + 1)) {
                                        aggregatePathQueryResult.putTime(pageTimeStamps[pageTimeIndex]);
                                        aggregatePathQueryResult.putInt(update[0].getInt(updateIdx[0] / 2));
                                        aggregatePathQueryResult.insertTrueIndex++;
                                    } else {
                                        aggregatePathQueryResult.putTime(pageTimeStamps[pageTimeIndex]);
                                        aggregatePathQueryResult.putInt(v);
                                        aggregatePathQueryResult.insertTrueIndex++;
                                    }
                                }

                                commonTimestampsIndex += 1;
                                pageTimeIndex += 1;

                            } else if (pageTimeStamps[pageTimeIndex] < commonTimestamps.get(commonTimestampsIndex)) {
                                decoder.readInt(page);
                                pageTimeIndex += 1;
                            } else {
                                commonTimestampsIndex += 1;
                            }
                        }
                        // updateFalse data < updateTrue data.
                        else if (mode == 1) {
                            if (pageTimeStamps[pageTimeIndex] == commonTimestamps.get(commonTimestampsIndex)) {
                                int v = decoder.readInt(page);

                                if (timeFilter == null || timeVisitor.verify(pageTimeStamps[pageTimeIndex])) {
                                    if (update[1].getTime(updateIdx[1]) <= pageTimeStamps[pageTimeIndex]
                                            && pageTimeStamps[pageTimeIndex] <= update[1].getTime(updateIdx[1] + 1)) {
                                        logger.error("never reach here");
                                    } else {
                                        aggregatePathQueryResult.putTime(pageTimeStamps[pageTimeIndex]);
                                        aggregatePathQueryResult.putInt(v);
                                        aggregatePathQueryResult.insertTrueIndex++;
                                    }
                                }

                                commonTimestampsIndex += 1;
                                pageTimeIndex += 1;

                            } else if (pageTimeStamps[pageTimeIndex] < commonTimestamps.get(commonTimestampsIndex)) {
                                decoder.readInt(page);
                                pageTimeIndex += 1;
                            } else {
                                commonTimestampsIndex += 1;
                            }
                        }

                        // set the update array to next position that current time
                        while (mode != -1 && pageTimeIndex < pageTimeStamps.length
                                && pageTimeStamps[pageTimeIndex] > update[mode].getTime(updateIdx[mode] + 1)) {
                            updateIdx[mode] += 2;
                            mode = getNextMode(updateIdx[0], updateIdx[1], update[0], update[1]);
                        }

                        if (commonTimestampsIndex >= commonTimestamps.size()) {
                            break;
                        }
                    }

                    // still has page data, no common timestamps
                    if (decoder.hasNext(page) && commonTimestampsIndex >= commonTimestamps.size()) {
                        return new Pair<>(aggregatePathQueryResult, commonTimestampsIndex);
                    }

                    // still has common timestamps, no page data
                    if (!decoder.hasNext(page) && commonTimestampsIndex < commonTimestamps.size()) {
                        return new Pair<>(aggregatePathQueryResult, commonTimestampsIndex);
                    }

                    break;
                case BOOLEAN:
                    while (decoder.hasNext(page)) {
                        long commonTimestamp = commonTimestamps.get(commonTimestampsIndex);

                        while (insertMemoryData.hasInsertData() && pageTimeIndex < pageTimeStamps.length
                                && insertMemoryData.getCurrentMinTime() <= pageTimeStamps[pageTimeIndex]) {

                            if (insertMemoryData.getCurrentMinTime() == commonTimestamp) {
                                aggregatePathQueryResult.putTime(insertMemoryData.getCurrentMinTime());
                                aggregatePathQueryResult.putBoolean(insertMemoryData.getCurrentBooleanValue());
                                aggregatePathQueryResult.insertTrueIndex++;

                                // both insertMemory value and page value should be removed
                                if (insertMemoryData.getCurrentMinTime() == pageTimeStamps[pageTimeIndex]) {
                                    insertMemoryData.removeCurrentValue();
                                    pageTimeIndex++;
                                    decoder.readBoolean(page);
                                } else {
                                    insertMemoryData.removeCurrentValue();
                                }

                                commonTimestampsIndex += 1;
                                if (commonTimestampsIndex < commonTimestamps.size()) {
                                    commonTimestamp = commonTimestamps.get(commonTimestampsIndex);
                                } else {
                                    break;
                                }

                            } else if (insertMemoryData.getCurrentMinTime() < commonTimestamp){
                                insertMemoryData.removeCurrentValue();
                            } else {
                                commonTimestampsIndex += 1;
                                if (commonTimestampsIndex < commonTimestamps.size()) {
                                    commonTimestamp = commonTimestamps.get(commonTimestampsIndex);
                                } else {
                                    break;
                                }
                            }
                        }

                        if (!decoder.hasNext(page) || pageTimeIndex >= pageTimeStamps.length) {
                            break;
                        }
                        if (commonTimestampsIndex >= commonTimestamps.size()) {
                            break;
                        }

                        // compare with commonTimestamps firstly
                        // then compare with time filter
                        // lastly compare with update operation

                        // no updateTrue, no updateFalse
                        if (mode == -1) {
                            if (pageTimeStamps[pageTimeIndex] == commonTimestamps.get(commonTimestampsIndex)) {
                                if ((timeFilter == null || timeVisitor.verify(pageTimeStamps[pageTimeIndex]))) {
                                    aggregatePathQueryResult.putTime(pageTimeStamps[pageTimeIndex]);
                                    boolean v = decoder.readBoolean(page);
                                    aggregatePathQueryResult.putBoolean(v);
                                    aggregatePathQueryResult.insertTrueIndex++;
                                }

                                // no matter if time is satisfied with time filter, the two index will plus
                                commonTimestampsIndex += 1;
                                pageTimeIndex += 1;
                            } else if (pageTimeStamps[pageTimeIndex] < commonTimestamps.get(commonTimestampsIndex)) {
                                decoder.readBoolean(page);
                                pageTimeIndex += 1;
                            } else {
                                commonTimestampsIndex += 1;
                            }
                        }
                        // updateTrue data < updateFalse data.
                        else if (mode == 0) {
                            if (pageTimeStamps[pageTimeIndex] == commonTimestamps.get(commonTimestampsIndex)) {
                                boolean v = decoder.readBoolean(page);

                                if (timeFilter == null || timeVisitor.verify(pageTimeStamps[pageTimeIndex])) {
                                    // if updateTrue changes the original value
                                    if (update[0].getTime(updateIdx[0]) <= pageTimeStamps[pageTimeIndex]
                                            && pageTimeStamps[pageTimeIndex] <= update[0].getTime(updateIdx[0] + 1)) {
                                        aggregatePathQueryResult.putTime(pageTimeStamps[pageTimeIndex]);
                                        aggregatePathQueryResult.putBoolean(update[0].getBoolean(updateIdx[0] / 2));
                                        aggregatePathQueryResult.insertTrueIndex++;
                                    } else {
                                        aggregatePathQueryResult.putTime(pageTimeStamps[pageTimeIndex]);
                                        aggregatePathQueryResult.putBoolean(v);
                                        aggregatePathQueryResult.insertTrueIndex++;
                                    }
                                }

                                commonTimestampsIndex += 1;
                                pageTimeIndex += 1;

                            } else if (pageTimeStamps[pageTimeIndex] < commonTimestamps.get(commonTimestampsIndex)) {
                                decoder.readBoolean(page);
                                pageTimeIndex += 1;
                            } else {
                                commonTimestampsIndex += 1;
                            }
                        }
                        // updateFalse data < updateTrue data.
                        else if (mode == 1) {
                            if (pageTimeStamps[pageTimeIndex] == commonTimestamps.get(commonTimestampsIndex)) {
                                boolean v = decoder.readBoolean(page);

                                if (timeFilter == null || timeVisitor.verify(pageTimeStamps[pageTimeIndex])) {
                                    if (update[1].getTime(updateIdx[1]) <= pageTimeStamps[pageTimeIndex]
                                            && pageTimeStamps[pageTimeIndex] <= update[1].getTime(updateIdx[1] + 1)) {
                                        logger.error("never reach here");
                                    } else {
                                        aggregatePathQueryResult.putTime(pageTimeStamps[pageTimeIndex]);
                                        aggregatePathQueryResult.putBoolean(v);
                                        aggregatePathQueryResult.insertTrueIndex++;
                                    }
                                }

                                commonTimestampsIndex += 1;
                                pageTimeIndex += 1;

                            } else if (pageTimeStamps[pageTimeIndex] < commonTimestamps.get(commonTimestampsIndex)) {
                                decoder.readBoolean(page);
                                pageTimeIndex += 1;
                            } else {
                                commonTimestampsIndex += 1;
                            }
                        }

                        // set the update array to next position that current time
                        while (mode != -1 && pageTimeIndex < pageTimeStamps.length
                                && pageTimeStamps[pageTimeIndex] > update[mode].getTime(updateIdx[mode] + 1)) {
                            updateIdx[mode] += 2;
                            mode = getNextMode(updateIdx[0], updateIdx[1], update[0], update[1]);
                        }

                        if (commonTimestampsIndex >= commonTimestamps.size()) {
                            break;
                        }
                    }

                    // still has page data, no common timestamps
                    if (decoder.hasNext(page) && commonTimestampsIndex >= commonTimestamps.size()) {
                        return new Pair<>(aggregatePathQueryResult, commonTimestampsIndex);
                    }

                    // still has common timestamps, no page data
                    if (!decoder.hasNext(page) && commonTimestampsIndex < commonTimestamps.size()) {
                        return new Pair<>(aggregatePathQueryResult, commonTimestampsIndex);
                    }

                    break;
                case INT64:
                    while (decoder.hasNext(page)) {
                        long commonTimestamp = commonTimestamps.get(commonTimestampsIndex);

                        while (insertMemoryData.hasInsertData() && pageTimeIndex < pageTimeStamps.length
                                && insertMemoryData.getCurrentMinTime() <= pageTimeStamps[pageTimeIndex]) {

                            if (insertMemoryData.getCurrentMinTime() == commonTimestamp) {
                                aggregatePathQueryResult.putTime(insertMemoryData.getCurrentMinTime());
                                aggregatePathQueryResult.putLong(insertMemoryData.getCurrentLongValue());
                                aggregatePathQueryResult.insertTrueIndex++;

                                // both insertMemory value and page value should be removed
                                if (insertMemoryData.getCurrentMinTime() == pageTimeStamps[pageTimeIndex]) {
                                    insertMemoryData.removeCurrentValue();
                                    pageTimeIndex++;
                                    decoder.readLong(page);
                                } else {
                                    insertMemoryData.removeCurrentValue();
                                }

                                commonTimestampsIndex += 1;
                                if (commonTimestampsIndex < commonTimestamps.size()) {
                                    commonTimestamp = commonTimestamps.get(commonTimestampsIndex);
                                } else {
                                    break;
                                }

                            } else if (insertMemoryData.getCurrentMinTime() < commonTimestamp){
                                insertMemoryData.removeCurrentValue();
                            } else {
                                commonTimestampsIndex += 1;
                                if (commonTimestampsIndex < commonTimestamps.size()) {
                                    commonTimestamp = commonTimestamps.get(commonTimestampsIndex);
                                } else {
                                    break;
                                }
                            }
                        }

                        if (!decoder.hasNext(page) || pageTimeIndex >= pageTimeStamps.length) {
                            break;
                        }
                        if (commonTimestampsIndex >= commonTimestamps.size()) {
                            break;
                        }

                        // compare with commonTimestamps firstly
                        // then compare with time filter
                        // lastly compare with update operation

                        // no updateTrue, no updateFalse
                        if (mode == -1) {
                            if (pageTimeStamps[pageTimeIndex] == commonTimestamps.get(commonTimestampsIndex)) {
                                if ((timeFilter == null || timeVisitor.verify(pageTimeStamps[pageTimeIndex]))) {
                                    aggregatePathQueryResult.putTime(pageTimeStamps[pageTimeIndex]);
                                    long v = decoder.readLong(page);
                                    aggregatePathQueryResult.putLong(v);
                                    aggregatePathQueryResult.insertTrueIndex++;
                                }

                                // no matter if time is satisfied with time filter, the two index will plus
                                commonTimestampsIndex += 1;
                                pageTimeIndex += 1;
                            } else if (pageTimeStamps[pageTimeIndex] < commonTimestamps.get(commonTimestampsIndex)) {
                                decoder.readLong(page);
                                pageTimeIndex += 1;
                            } else {
                                commonTimestampsIndex += 1;
                            }
                        }
                        // updateTrue data < updateFalse data.
                        else if (mode == 0) {
                            if (pageTimeStamps[pageTimeIndex] == commonTimestamps.get(commonTimestampsIndex)) {
                                Long v = decoder.readLong(page);

                                if (timeFilter == null || timeVisitor.verify(pageTimeStamps[pageTimeIndex])) {
                                    // if updateTrue changes the original value
                                    if (update[0].getTime(updateIdx[0]) <= pageTimeStamps[pageTimeIndex]
                                            && pageTimeStamps[pageTimeIndex] <= update[0].getTime(updateIdx[0] + 1)) {
                                        aggregatePathQueryResult.putTime(pageTimeStamps[pageTimeIndex]);
                                        aggregatePathQueryResult.putLong(update[0].getLong(updateIdx[0] / 2));
                                        aggregatePathQueryResult.insertTrueIndex++;
                                    } else {
                                        aggregatePathQueryResult.putTime(pageTimeStamps[pageTimeIndex]);
                                        aggregatePathQueryResult.putLong(v);
                                        aggregatePathQueryResult.insertTrueIndex++;
                                    }
                                }

                                commonTimestampsIndex += 1;
                                pageTimeIndex += 1;

                            } else if (pageTimeStamps[pageTimeIndex] < commonTimestamps.get(commonTimestampsIndex)) {
                                decoder.readLong(page);
                                pageTimeIndex += 1;
                            } else {
                                commonTimestampsIndex += 1;
                            }
                        }
                        // updateFalse data < updateTrue data.
                        else if (mode == 1) {
                            if (pageTimeStamps[pageTimeIndex] == commonTimestamps.get(commonTimestampsIndex)) {
                                long v = decoder.readLong(page);

                                if (timeFilter == null || timeVisitor.verify(pageTimeStamps[pageTimeIndex])) {
                                    if (update[1].getTime(updateIdx[1]) <= pageTimeStamps[pageTimeIndex]
                                            && pageTimeStamps[pageTimeIndex] <= update[1].getTime(updateIdx[1] + 1)) {
                                        logger.error("never reach here");
                                    } else {
                                        aggregatePathQueryResult.putTime(pageTimeStamps[pageTimeIndex]);
                                        aggregatePathQueryResult.putLong(v);
                                        aggregatePathQueryResult.insertTrueIndex++;
                                    }
                                }

                                commonTimestampsIndex += 1;
                                pageTimeIndex += 1;

                            } else if (pageTimeStamps[pageTimeIndex] < commonTimestamps.get(commonTimestampsIndex)) {
                                decoder.readLong(page);
                                pageTimeIndex += 1;
                            } else {
                                commonTimestampsIndex += 1;
                            }
                        }

                        // set the update array to next position that current time
                        while (mode != -1 && pageTimeIndex < pageTimeStamps.length
                                && pageTimeStamps[pageTimeIndex] > update[mode].getTime(updateIdx[mode] + 1)) {
                            updateIdx[mode] += 2;
                            mode = getNextMode(updateIdx[0], updateIdx[1], update[0], update[1]);
                        }

                        if (commonTimestampsIndex >= commonTimestamps.size()) {
                            break;
                        }
                    }

                    // still has page data, no common timestamps
                    if (decoder.hasNext(page) && commonTimestampsIndex >= commonTimestamps.size()) {
                        return new Pair<>(aggregatePathQueryResult, commonTimestampsIndex);
                    }

                    // still has common timestamps, no page data
                    if (!decoder.hasNext(page) && commonTimestampsIndex < commonTimestamps.size()) {
                        return new Pair<>(aggregatePathQueryResult, commonTimestampsIndex);
                    }

                    break;
                case FLOAT:
                    while (decoder.hasNext(page)) {
                        long commonTimestamp = commonTimestamps.get(commonTimestampsIndex);

                        while (insertMemoryData.hasInsertData() && pageTimeIndex < pageTimeStamps.length
                                && insertMemoryData.getCurrentMinTime() <= pageTimeStamps[pageTimeIndex]) {

                            if (insertMemoryData.getCurrentMinTime() == commonTimestamp) {
                                aggregatePathQueryResult.putTime(insertMemoryData.getCurrentMinTime());
                                aggregatePathQueryResult.putFloat(insertMemoryData.getCurrentFloatValue());
                                aggregatePathQueryResult.insertTrueIndex++;

                                // both insertMemory value and page value should be removed
                                if (insertMemoryData.getCurrentMinTime() == pageTimeStamps[pageTimeIndex]) {
                                    insertMemoryData.removeCurrentValue();
                                    pageTimeIndex++;
                                    decoder.readFloat(page);
                                } else {
                                    insertMemoryData.removeCurrentValue();
                                }

                                commonTimestampsIndex += 1;
                                if (commonTimestampsIndex < commonTimestamps.size()) {
                                    commonTimestamp = commonTimestamps.get(commonTimestampsIndex);
                                } else {
                                    break;
                                }

                            } else if (insertMemoryData.getCurrentMinTime() < commonTimestamp){
                                insertMemoryData.removeCurrentValue();
                            } else {
                                commonTimestampsIndex += 1;
                                if (commonTimestampsIndex < commonTimestamps.size()) {
                                    commonTimestamp = commonTimestamps.get(commonTimestampsIndex);
                                } else {
                                    break;
                                }
                            }
                        }

                        if (!decoder.hasNext(page) || pageTimeIndex >= pageTimeStamps.length) {
                            break;
                        }
                        if (commonTimestampsIndex >= commonTimestamps.size()) {
                            break;
                        }

                        // compare with commonTimestamps firstly
                        // then compare with time filter
                        // lastly compare with update operation

                        // no updateTrue, no updateFalse
                        if (mode == -1) {
                            if (pageTimeStamps[pageTimeIndex] == commonTimestamps.get(commonTimestampsIndex)) {
                                if ((timeFilter == null || timeVisitor.verify(pageTimeStamps[pageTimeIndex]))) {
                                    aggregatePathQueryResult.putTime(pageTimeStamps[pageTimeIndex]);
                                    float v = decoder.readFloat(page);
                                    aggregatePathQueryResult.putFloat(v);
                                    aggregatePathQueryResult.insertTrueIndex++;
                                }

                                // no matter if time is satisfied with time filter, the two index will plus
                                commonTimestampsIndex += 1;
                                pageTimeIndex += 1;
                            } else if (pageTimeStamps[pageTimeIndex] < commonTimestamps.get(commonTimestampsIndex)) {
                                decoder.readFloat(page);
                                pageTimeIndex += 1;
                            } else {
                                commonTimestampsIndex += 1;
                            }
                        }
                        // updateTrue data < updateFalse data.
                        else if (mode == 0) {
                            if (pageTimeStamps[pageTimeIndex] == commonTimestamps.get(commonTimestampsIndex)) {
                                float v = decoder.readFloat(page);

                                if (timeFilter == null || timeVisitor.verify(pageTimeStamps[pageTimeIndex])) {
                                    // if updateTrue changes the original value
                                    if (update[0].getTime(updateIdx[0]) <= pageTimeStamps[pageTimeIndex]
                                            && pageTimeStamps[pageTimeIndex] <= update[0].getTime(updateIdx[0] + 1)) {
                                        aggregatePathQueryResult.putTime(pageTimeStamps[pageTimeIndex]);
                                        aggregatePathQueryResult.putFloat(update[0].getFloat(updateIdx[0] / 2));
                                        aggregatePathQueryResult.insertTrueIndex++;
                                    } else {
                                        aggregatePathQueryResult.putTime(pageTimeStamps[pageTimeIndex]);
                                        aggregatePathQueryResult.putFloat(v);
                                        aggregatePathQueryResult.insertTrueIndex++;
                                    }
                                }

                                commonTimestampsIndex += 1;
                                pageTimeIndex += 1;

                            } else if (pageTimeStamps[pageTimeIndex] < commonTimestamps.get(commonTimestampsIndex)) {
                                decoder.readFloat(page);
                                pageTimeIndex += 1;
                            } else {
                                commonTimestampsIndex += 1;
                            }
                        }
                        // updateFalse data < updateTrue data.
                        else if (mode == 1) {
                            if (pageTimeStamps[pageTimeIndex] == commonTimestamps.get(commonTimestampsIndex)) {
                                float v = decoder.readFloat(page);

                                if (timeFilter == null || timeVisitor.verify(pageTimeStamps[pageTimeIndex])) {
                                    if (update[1].getTime(updateIdx[1]) <= pageTimeStamps[pageTimeIndex]
                                            && pageTimeStamps[pageTimeIndex] <= update[1].getTime(updateIdx[1] + 1)) {
                                        logger.error("never reach here");
                                    } else {
                                        aggregatePathQueryResult.putTime(pageTimeStamps[pageTimeIndex]);
                                        aggregatePathQueryResult.putFloat(v);
                                        aggregatePathQueryResult.insertTrueIndex++;
                                    }
                                }

                                commonTimestampsIndex += 1;
                                pageTimeIndex += 1;

                            } else if (pageTimeStamps[pageTimeIndex] < commonTimestamps.get(commonTimestampsIndex)) {
                                decoder.readFloat(page);
                                pageTimeIndex += 1;
                            } else {
                                commonTimestampsIndex += 1;
                            }
                        }

                        // set the update array to next position that current time
                        while (mode != -1 && pageTimeIndex < pageTimeStamps.length
                                && pageTimeStamps[pageTimeIndex] > update[mode].getTime(updateIdx[mode] + 1)) {
                            updateIdx[mode] += 2;
                            mode = getNextMode(updateIdx[0], updateIdx[1], update[0], update[1]);
                        }

                        if (commonTimestampsIndex >= commonTimestamps.size()) {
                            break;
                        }
                    }

                    // still has page data, no common timestamps
                    if (decoder.hasNext(page) && commonTimestampsIndex >= commonTimestamps.size()) {
                        return new Pair<>(aggregatePathQueryResult, commonTimestampsIndex);
                    }

                    // still has common timestamps, no page data
                    if (!decoder.hasNext(page) && commonTimestampsIndex < commonTimestamps.size()) {
                        return new Pair<>(aggregatePathQueryResult, commonTimestampsIndex);
                    }

                    break;
                case DOUBLE:
                    while (decoder.hasNext(page)) {
                        long commonTimestamp = commonTimestamps.get(commonTimestampsIndex);

                        while (insertMemoryData.hasInsertData() && pageTimeIndex < pageTimeStamps.length
                                && insertMemoryData.getCurrentMinTime() <= pageTimeStamps[pageTimeIndex]) {

                            if (insertMemoryData.getCurrentMinTime() == commonTimestamp) {
                                aggregatePathQueryResult.putTime(insertMemoryData.getCurrentMinTime());
                                aggregatePathQueryResult.putDouble(insertMemoryData.getCurrentDoubleValue());
                                aggregatePathQueryResult.insertTrueIndex++;

                                // both insertMemory value and page value should be removed
                                if (insertMemoryData.getCurrentMinTime() == pageTimeStamps[pageTimeIndex]) {
                                    insertMemoryData.removeCurrentValue();
                                    pageTimeIndex++;
                                    decoder.readDouble(page);
                                } else {
                                    insertMemoryData.removeCurrentValue();
                                }

                                commonTimestampsIndex += 1;
                                if (commonTimestampsIndex < commonTimestamps.size()) {
                                    commonTimestamp = commonTimestamps.get(commonTimestampsIndex);
                                } else {
                                    break;
                                }

                            } else if (insertMemoryData.getCurrentMinTime() < commonTimestamp){
                                insertMemoryData.removeCurrentValue();
                            } else {
                                commonTimestampsIndex += 1;
                                if (commonTimestampsIndex < commonTimestamps.size()) {
                                    commonTimestamp = commonTimestamps.get(commonTimestampsIndex);
                                } else {
                                    break;
                                }
                            }
                        }

                        if (!decoder.hasNext(page) || pageTimeIndex >= pageTimeStamps.length) {
                            break;
                        }
                        if (commonTimestampsIndex >= commonTimestamps.size()) {
                            break;
                        }

                        // compare with commonTimestamps firstly
                        // then compare with time filter
                        // lastly compare with update operation

                        // no updateTrue, no updateFalse
                        if (mode == -1) {
                            if (pageTimeStamps[pageTimeIndex] == commonTimestamps.get(commonTimestampsIndex)) {
                                if ((timeFilter == null || timeVisitor.verify(pageTimeStamps[pageTimeIndex]))) {
                                    aggregatePathQueryResult.putTime(pageTimeStamps[pageTimeIndex]);
                                    double v = decoder.readDouble(page);
                                    aggregatePathQueryResult.putDouble(v);
                                    aggregatePathQueryResult.insertTrueIndex++;
                                }

                                // no matter if time is satisfied with time filter, the two index will plus
                                commonTimestampsIndex += 1;
                                pageTimeIndex += 1;
                            } else if (pageTimeStamps[pageTimeIndex] < commonTimestamps.get(commonTimestampsIndex)) {
                                decoder.readDouble(page);
                                pageTimeIndex += 1;
                            } else {
                                commonTimestampsIndex += 1;
                            }
                        }
                        // updateTrue data < updateFalse data.
                        else if (mode == 0) {
                            if (pageTimeStamps[pageTimeIndex] == commonTimestamps.get(commonTimestampsIndex)) {
                                double v = decoder.readDouble(page);

                                if (timeFilter == null || timeVisitor.verify(pageTimeStamps[pageTimeIndex])) {
                                    // if updateTrue changes the original value
                                    if (update[0].getTime(updateIdx[0]) <= pageTimeStamps[pageTimeIndex]
                                            && pageTimeStamps[pageTimeIndex] <= update[0].getTime(updateIdx[0] + 1)) {
                                        aggregatePathQueryResult.putTime(pageTimeStamps[pageTimeIndex]);
                                        aggregatePathQueryResult.putDouble(update[0].getDouble(updateIdx[0] / 2));
                                        aggregatePathQueryResult.insertTrueIndex++;
                                    } else {
                                        aggregatePathQueryResult.putTime(pageTimeStamps[pageTimeIndex]);
                                        aggregatePathQueryResult.putDouble(v);
                                        aggregatePathQueryResult.insertTrueIndex++;
                                    }
                                }

                                commonTimestampsIndex += 1;
                                pageTimeIndex += 1;

                            } else if (pageTimeStamps[pageTimeIndex] < commonTimestamps.get(commonTimestampsIndex)) {
                                decoder.readDouble(page);
                                pageTimeIndex += 1;
                            } else {
                                commonTimestampsIndex += 1;
                            }
                        }
                        // updateFalse data < updateTrue data.
                        else if (mode == 1) {
                            if (pageTimeStamps[pageTimeIndex] == commonTimestamps.get(commonTimestampsIndex)) {
                                double v = decoder.readDouble(page);

                                if (timeFilter == null || timeVisitor.verify(pageTimeStamps[pageTimeIndex])) {
                                    if (update[1].getTime(updateIdx[1]) <= pageTimeStamps[pageTimeIndex]
                                            && pageTimeStamps[pageTimeIndex] <= update[1].getTime(updateIdx[1] + 1)) {
                                        logger.error("never reach here");
                                    } else {
                                        aggregatePathQueryResult.putTime(pageTimeStamps[pageTimeIndex]);
                                        aggregatePathQueryResult.putDouble(v);
                                        aggregatePathQueryResult.insertTrueIndex++;
                                    }
                                }

                                commonTimestampsIndex += 1;
                                pageTimeIndex += 1;

                            } else if (pageTimeStamps[pageTimeIndex] < commonTimestamps.get(commonTimestampsIndex)) {
                                decoder.readDouble(page);
                                pageTimeIndex += 1;
                            } else {
                                commonTimestampsIndex += 1;
                            }
                        }

                        // set the update array to next position that current time
                        while (mode != -1 && pageTimeIndex < pageTimeStamps.length
                                && pageTimeStamps[pageTimeIndex] > update[mode].getTime(updateIdx[mode] + 1)) {
                            updateIdx[mode] += 2;
                            mode = getNextMode(updateIdx[0], updateIdx[1], update[0], update[1]);
                        }

                        if (commonTimestampsIndex >= commonTimestamps.size()) {
                            break;
                        }
                    }

                    // still has page data, no common timestamps
                    if (decoder.hasNext(page) && commonTimestampsIndex >= commonTimestamps.size()) {
                        return new Pair<>(aggregatePathQueryResult, commonTimestampsIndex);
                    }

                    // still has common timestamps, no page data
                    if (!decoder.hasNext(page) && commonTimestampsIndex < commonTimestamps.size()) {
                        return new Pair<>(aggregatePathQueryResult, commonTimestampsIndex);
                    }

                    break;
                case TEXT:
                    while (decoder.hasNext(page)) {
                        long commonTimestamp = commonTimestamps.get(commonTimestampsIndex);

                        while (insertMemoryData.hasInsertData() && pageTimeIndex < pageTimeStamps.length
                                && insertMemoryData.getCurrentMinTime() <= pageTimeStamps[pageTimeIndex]) {

                            if (insertMemoryData.getCurrentMinTime() == commonTimestamp) {
                                aggregatePathQueryResult.putTime(insertMemoryData.getCurrentMinTime());
                                aggregatePathQueryResult.putBinary(insertMemoryData.getCurrentBinaryValue());
                                aggregatePathQueryResult.insertTrueIndex++;

                                // both insertMemory value and page value should be removed
                                if (insertMemoryData.getCurrentMinTime() == pageTimeStamps[pageTimeIndex]) {
                                    insertMemoryData.removeCurrentValue();
                                    pageTimeIndex++;
                                    decoder.readBinary(page);
                                } else {
                                    insertMemoryData.removeCurrentValue();
                                }

                                commonTimestampsIndex += 1;
                                if (commonTimestampsIndex < commonTimestamps.size()) {
                                    commonTimestamp = commonTimestamps.get(commonTimestampsIndex);
                                } else {
                                    break;
                                }

                            } else if (insertMemoryData.getCurrentMinTime() < commonTimestamp){
                                insertMemoryData.removeCurrentValue();
                            } else {
                                commonTimestampsIndex += 1;
                                if (commonTimestampsIndex < commonTimestamps.size()) {
                                    commonTimestamp = commonTimestamps.get(commonTimestampsIndex);
                                } else {
                                    break;
                                }
                            }
                        }

                        if (!decoder.hasNext(page) || pageTimeIndex >= pageTimeStamps.length) {
                            break;
                        }
                        if (commonTimestampsIndex >= commonTimestamps.size()) {
                            break;
                        }

                        // compare with commonTimestamps firstly
                        // then compare with time filter
                        // lastly compare with update operation

                        // no updateTrue, no updateFalse
                        if (mode == -1) {
                            if (pageTimeStamps[pageTimeIndex] == commonTimestamps.get(commonTimestampsIndex)) {
                                if ((timeFilter == null || timeVisitor.verify(pageTimeStamps[pageTimeIndex]))) {
                                    aggregatePathQueryResult.putTime(pageTimeStamps[pageTimeIndex]);
                                    Binary v = decoder.readBinary(page);
                                    aggregatePathQueryResult.putBinary(v);
                                    aggregatePathQueryResult.insertTrueIndex++;
                                }

                                // no matter if time is satisfied with time filter, the two index will plus
                                commonTimestampsIndex += 1;
                                pageTimeIndex += 1;
                            } else if (pageTimeStamps[pageTimeIndex] < commonTimestamps.get(commonTimestampsIndex)) {
                                decoder.readBinary(page);
                                pageTimeIndex += 1;
                            } else {
                                commonTimestampsIndex += 1;
                            }
                        }
                        // updateTrue data < updateFalse data.
                        else if (mode == 0) {
                            if (pageTimeStamps[pageTimeIndex] == commonTimestamps.get(commonTimestampsIndex)) {
                                Binary v = decoder.readBinary(page);

                                if (timeFilter == null || timeVisitor.verify(pageTimeStamps[pageTimeIndex])) {
                                    // if updateTrue changes the original value
                                    if (update[0].getTime(updateIdx[0]) <= pageTimeStamps[pageTimeIndex]
                                            && pageTimeStamps[pageTimeIndex] <= update[0].getTime(updateIdx[0] + 1)) {
                                        aggregatePathQueryResult.putTime(pageTimeStamps[pageTimeIndex]);
                                        aggregatePathQueryResult.putBinary(update[0].getBinary(updateIdx[0] / 2));
                                        aggregatePathQueryResult.insertTrueIndex++;
                                    } else {
                                        aggregatePathQueryResult.putTime(pageTimeStamps[pageTimeIndex]);
                                        aggregatePathQueryResult.putBinary(v);
                                        aggregatePathQueryResult.insertTrueIndex++;
                                    }
                                }

                                commonTimestampsIndex += 1;
                                pageTimeIndex += 1;

                            } else if (pageTimeStamps[pageTimeIndex] < commonTimestamps.get(commonTimestampsIndex)) {
                                decoder.readBinary(page);
                                pageTimeIndex += 1;
                            } else {
                                commonTimestampsIndex += 1;
                            }
                        }
                        // updateFalse data < updateTrue data.
                        else if (mode == 1) {
                            if (pageTimeStamps[pageTimeIndex] == commonTimestamps.get(commonTimestampsIndex)) {
                                Binary v = decoder.readBinary(page);

                                if (timeFilter == null || timeVisitor.verify(pageTimeStamps[pageTimeIndex])) {
                                    if (update[1].getTime(updateIdx[1]) <= pageTimeStamps[pageTimeIndex]
                                            && pageTimeStamps[pageTimeIndex] <= update[1].getTime(updateIdx[1] + 1)) {
                                        logger.error("never reach here");
                                    } else {
                                        aggregatePathQueryResult.putTime(pageTimeStamps[pageTimeIndex]);
                                        aggregatePathQueryResult.putBinary(v);
                                        aggregatePathQueryResult.insertTrueIndex++;
                                    }
                                }

                                commonTimestampsIndex += 1;
                                pageTimeIndex += 1;

                            } else if (pageTimeStamps[pageTimeIndex] < commonTimestamps.get(commonTimestampsIndex)) {
                                decoder.readBinary(page);
                                pageTimeIndex += 1;
                            } else {
                                commonTimestampsIndex += 1;
                            }
                        }

                        // set the update array to next position that current time
                        while (mode != -1 && pageTimeIndex < pageTimeStamps.length
                                && pageTimeStamps[pageTimeIndex] > update[mode].getTime(updateIdx[mode] + 1)) {
                            updateIdx[mode] += 2;
                            mode = getNextMode(updateIdx[0], updateIdx[1], update[0], update[1]);
                        }

                        if (commonTimestampsIndex >= commonTimestamps.size()) {
                            break;
                        }
                    }

                    // still has page data, no common timestamps
                    if (decoder.hasNext(page) && commonTimestampsIndex >= commonTimestamps.size()) {
                        return new Pair<>(aggregatePathQueryResult, commonTimestampsIndex);
                    }

                    // still has common timestamps, no page data
                    if (!decoder.hasNext(page) && commonTimestampsIndex < commonTimestamps.size()) {
                        return new Pair<>(aggregatePathQueryResult, commonTimestampsIndex);
                    }

                    break;
                default:
                    throw new IOException("Data type not support : " + dataType);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        // update the curIdx in updateTrue and updateFalse
        update[0].curIdx = updateIdx[0];
        update[1].curIdx = updateIdx[1];
        return new Pair<>(aggregatePathQueryResult, 0);
    }
}