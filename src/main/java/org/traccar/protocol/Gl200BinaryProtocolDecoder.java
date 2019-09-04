/*
 * Copyright 2017 - 2018 Anton Tananaev (anton@traccar.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.traccar.protocol;

import com.sun.javafx.image.impl.BaseIntToByteConverter;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import org.traccar.BaseProtocolDecoder;
import org.traccar.DeviceSession;
import org.traccar.Protocol;
import org.traccar.helper.BitBuffer;
import org.traccar.helper.BitUtil;
import org.traccar.helper.DateBuilder;
import org.traccar.helper.UnitsConverter;
import org.traccar.model.CellTower;
import org.traccar.model.Network;
import org.traccar.model.Position;
import sun.security.util.BitArray;

import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;

public class Gl200BinaryProtocolDecoder extends BaseProtocolDecoder {

    public Gl200BinaryProtocolDecoder(Protocol protocol) {
        super(protocol);
    }

    private Date decodeTime(ByteBuf buf) {
        DateBuilder dateBuilder = new DateBuilder()
                .setDate(buf.readUnsignedShort(), buf.readUnsignedByte(), buf.readUnsignedByte())
                .setTime(buf.readUnsignedByte(), buf.readUnsignedByte(), buf.readUnsignedByte());
        return dateBuilder.getDate();
    }

    private long readValue(ByteBuf buf, int length, boolean signed) {
        switch (length) {
            case 1:
                return signed ? buf.readByte() : buf.readUnsignedByte();
            case 2:
                return signed ? buf.readShort() : buf.readUnsignedShort();
            case 4:
                return signed ? buf.readInt() : buf.readUnsignedInt();
            default:
                return buf.readLong();
        }
    }

    private String decodeDeviceId(ByteBuf buf) {
        String devId = "";
        for (int i = 0; i < 7; i++)
            devId = devId + String.format("%02d", buf.readUnsignedByte());

        devId = devId + buf.readUnsignedByte();
        System.out.println(devId);
        return devId;
    }

    private Double decodeSpeed(ByteBuf buf) {
        Double speed = (double) buf.readUnsignedShort();

        speed = speed + (buf.readUnsignedByte() * 0.1);

        return UnitsConverter.knotsFromKph(speed);
    }

    private void decodePosition(Position position, ByteBuf buf) {

        int hdop = buf.readUnsignedByte();
        position.setValid(hdop > 0);
        position.set(Position.KEY_HDOP, hdop);

        position.setSpeed(decodeSpeed(buf));
        position.setCourse(buf.readUnsignedShort());
        position.setAltitude(buf.readShort());
        position.setLongitude(buf.readInt() * 0.000001);
        position.setLatitude(buf.readInt() * 0.000001);

        position.setTime(decodeTime(buf));

    }

    private void decodeNetwork(Position position, ByteBuf buf) {
        position.setNetwork(new Network(CellTower.from(
                buf.readUnsignedShort(), //MCC
                buf.readUnsignedShort(), //MNC
                buf.readUnsignedShort(), //LAC
                buf.readUnsignedShort()))); //CID
        buf.readUnsignedByte(); // reserved
    }

    public static final int MSG_RSP_LCB = 3;
    public static final int MSG_RSP_GEO = 8;
    public static final int MSG_RSP_COMPRESSED = 100;

    private List<Position> decodeLocation(Channel channel, SocketAddress remoteAddress, ByteBuf buf) {

        List<Position> positions = new LinkedList<>();

        int type = buf.readUnsignedByte();
        boolean[] maskBite = toBinary(buf.readUnsignedInt(), 32);
        if (maskBite[maskBite.length - 8]) {
            buf.readUnsignedShort(); // length
        }
        if (maskBite[maskBite.length - 9]) {
            buf.readUnsignedByte(); // device type
        }
        if (maskBite[maskBite.length - 10]) {
            buf.readUnsignedShort(); // protocol version
        }
        if (maskBite[maskBite.length - 11]) {
            buf.readUnsignedShort(); // firmware version
        }

        DeviceSession deviceSession = getDeviceSession(channel, remoteAddress, decodeDeviceId(buf));
        if (deviceSession == null) {
            return null;
        }

        if (maskBite[maskBite.length - 14]) {
            buf.skipBytes(17); // vin num
        }
        int battery = 0;
        double power = 0;
        int rpm = 0;
        int fuelConsumption = 0;
        int fuelLevel = 0;

        if (maskBite[maskBite.length - 12]) {
            battery = buf.readUnsignedByte();
        }
        if (maskBite[maskBite.length - 13]) {
            power = buf.readUnsignedShort() * 0.001;
        }
        if (maskBite[maskBite.length - 15]) {
            rpm = buf.readUnsignedShort();
        }
        if (maskBite[maskBite.length - 16]) {
            fuelConsumption = buf.readUnsignedShort();
        }
        if (maskBite[maskBite.length - 17]) {
            fuelLevel = buf.readUnsignedByte();
        }
        if (type == MSG_RSP_GEO) {
            buf.readUnsignedByte(); // reserved
            buf.readUnsignedByte(); // reserved
        }

        buf.readUnsignedByte(); // motion status
        int satellites = buf.readUnsignedByte();

        if (type != MSG_RSP_COMPRESSED) {
            buf.readUnsignedByte(); // index
        }
        if (type == MSG_RSP_LCB) {
            buf.readUnsignedByte(); // phone length
            for (int b = buf.readUnsignedByte(); ; b = buf.readUnsignedByte()) {
                if ((b & 0xf) == 0xf || (b & 0xf0) == 0xf0) {
                    break;
                }
            }
        }

        if (type == MSG_RSP_COMPRESSED) {

            int count = buf.readUnsignedShort();

            BitBuffer bits;
            int speed = 0;
            int heading = 0;
            int latitude = 0;
            int longitude = 0;
            long time = 0;

            for (int i = 0; i < count; i++) {

                if (time > 0) {
                    time += 1;
                }

                Position position = new Position(getProtocolName());
                position.setDeviceId(deviceSession.getDeviceId());

                switch (BitUtil.from(buf.getUnsignedByte(buf.readerIndex()), 8 - 2)) {
                    case 1:
                        bits = new BitBuffer(buf.readSlice(3));
                        bits.readUnsigned(2); // point attribute
                        bits.readUnsigned(1); // fix type
                        speed = bits.readUnsigned(12);
                        heading = bits.readUnsigned(9);
                        longitude = buf.readInt();
                        latitude = buf.readInt();
                        if (time == 0) {
                            time = buf.readUnsignedInt();
                        }
                        break;
                    case 2:
                        bits = new BitBuffer(buf.readSlice(5));
                        bits.readUnsigned(2); // point attribute
                        bits.readUnsigned(1); // fix type
                        speed += bits.readSigned(7);
                        heading += bits.readSigned(7);
                        longitude += bits.readSigned(12);
                        latitude += bits.readSigned(11);
                        break;
                    default:
                        buf.readUnsignedByte(); // invalid or same
                        continue;
                }

                position.setValid(true);
                position.setTime(new Date(time * 1000));
                position.setSpeed(UnitsConverter.knotsFromKph(speed * 0.1));
                position.setCourse(heading);
                position.setLongitude(longitude * 0.000001);
                position.setLatitude(latitude * 0.000001);

                positions.add(position);

            }

        } else {

            int count = buf.readUnsignedByte();

            for (int i = 0; i < count; i++) {

                Position position = new Position(getProtocolName());
                position.setDeviceId(deviceSession.getDeviceId());

                position.set(Position.KEY_BATTERY_LEVEL, battery);
                position.set(Position.KEY_POWER, power);
                position.set(Position.KEY_RPM, rpm);
                position.set(Position.KEY_FUEL_CONSUMPTION, fuelConsumption);
                position.set(Position.KEY_FUEL_LEVEL, fuelLevel);

                position.set(Position.KEY_SATELLITES, satellites);
                position.set("type", "FRI");

                decodePosition(position, buf);

                decodeNetwork(position, buf);

                positions.add(position);

            }

        }

        return positions;
    }

    public static final int MSG_EVT_BPL = 6;
    public static final int MSG_EVT_VGN = 45;
    public static final int MSG_EVT_VGF = 46;
    public static final int MSG_EVT_UPD = 15;
    public static final int MSG_EVT_IDF = 17;
    public static final int MSG_EVT_GSS = 21;
    public static final int MSG_EVT_GES = 26;
    public static final int MSG_EVT_GPJ = 31;
    public static final int MSG_EVT_RMD = 35;
    public static final int MSG_EVT_JDS = 33;
    public static final int MSG_EVT_CRA = 23;
    public static final int MSG_EVT_UPC = 34;

    private Position decodeEvent(Channel channel, SocketAddress remoteAddress, ByteBuf buf) {

        Position position = new Position(getProtocolName());

        int type = buf.readUnsignedByte();

        buf.readUnsignedInt(); // mask
        buf.readUnsignedShort(); // length
        buf.readUnsignedByte(); // device type
        buf.readUnsignedShort(); // protocol version

        position.set(Position.KEY_VERSION_FW, String.valueOf(buf.readUnsignedShort()));

        DeviceSession deviceSession = getDeviceSession(channel, remoteAddress, decodeDeviceId(buf));
        if (deviceSession == null) {
            return null;
        }
        position.setDeviceId(deviceSession.getDeviceId());

        position.set(Position.KEY_BATTERY_LEVEL, buf.readUnsignedByte());
        position.set(Position.KEY_POWER, buf.readUnsignedShort());

        buf.readUnsignedByte(); // motion status

        position.set(Position.KEY_SATELLITES, buf.readUnsignedByte());

        switch (type) {
            case MSG_EVT_BPL:
                buf.readUnsignedShort(); // backup battery voltage
                break;
            case MSG_EVT_VGN:
            case MSG_EVT_VGF:
                buf.readUnsignedShort(); // reserved
                buf.readUnsignedByte(); // report type
                buf.readUnsignedInt(); // ignition duration
                break;
            case MSG_EVT_UPD:
                buf.readUnsignedShort(); // code
                buf.readUnsignedByte(); // retry
                break;
            case MSG_EVT_IDF:
                buf.readUnsignedInt(); // idling duration
                break;
            case MSG_EVT_GSS:
                buf.readUnsignedByte(); // gps signal status
                buf.readUnsignedInt(); // reserved
                break;
            case MSG_EVT_GES:
                buf.readUnsignedShort(); // trigger geo id
                buf.readUnsignedByte(); // trigger geo enable
                buf.readUnsignedByte(); // trigger mode
                buf.readUnsignedInt(); // radius
                buf.readUnsignedInt(); // check interval
                break;
            case MSG_EVT_GPJ:
                buf.readUnsignedByte(); // cw jamming value
                buf.readUnsignedByte(); // gps jamming state
                break;
            case MSG_EVT_RMD:
                buf.readUnsignedByte(); // roaming state
                break;
            case MSG_EVT_JDS:
                buf.readUnsignedByte(); // jamming state
                break;
            case MSG_EVT_CRA:
                buf.readUnsignedByte(); // crash counter
                break;
            case MSG_EVT_UPC:
                buf.readUnsignedByte(); // command id
                buf.readUnsignedShort(); // result
                break;
            default:
                break;
        }

        buf.readUnsignedByte(); // count

        int hdop = buf.readUnsignedByte();
        position.setValid(hdop > 0);
        position.set(Position.KEY_HDOP, hdop);

        position.setSpeed(decodeSpeed(buf));
        position.setCourse(buf.readUnsignedShort());
        position.setAltitude(buf.readShort());
        position.setLongitude(buf.readInt() * 0.000001);
        position.setLatitude(buf.readInt() * 0.000001);

        position.setTime(decodeTime(buf));

        position.setNetwork(new Network(CellTower.from(
                buf.readUnsignedShort(), buf.readUnsignedShort(),
                buf.readUnsignedShort(), buf.readUnsignedShort())));

        buf.readUnsignedByte(); // reserved

        return position;
    }

    public static final int MSG_INF_GPS = 2;
    public static final int MSG_INF_CID = 4;
    public static final int MSG_INF_CSQ = 5;
    public static final int MSG_INF_VER = 6;
    public static final int MSG_INF_BAT = 7;
    public static final int MSG_INF_TMZ = 9;
    public static final int MSG_INF_GIR = 10;

    private Position decodeInformation(Channel channel, SocketAddress remoteAddress, ByteBuf buf) {

        Position position = new Position(getProtocolName());

        int type = buf.readUnsignedByte();

        buf.readUnsignedInt(); // mask
        buf.readUnsignedShort(); // length

        DeviceSession deviceSession = getDeviceSession(channel, remoteAddress, decodeDeviceId(buf));
        if (deviceSession == null) {
            return null;
        }
        position.setDeviceId(deviceSession.getDeviceId());

        buf.readUnsignedByte(); // device type
        buf.readUnsignedShort(); // protocol version

        position.set(Position.KEY_VERSION_FW, String.valueOf(buf.readUnsignedShort()));

        if (type == MSG_INF_VER) {
            buf.readUnsignedShort(); // hardware version
            buf.readUnsignedShort(); // mcu version
            buf.readUnsignedShort(); // reserved
        }

        buf.readUnsignedByte(); // motion status
        buf.readUnsignedByte(); // reserved

        position.set(Position.KEY_SATELLITES, buf.readUnsignedByte());

        buf.readUnsignedByte(); // mode
        buf.skipBytes(7); // last fix time
        buf.readUnsignedByte(); // reserved
        buf.readUnsignedByte();
        buf.readUnsignedShort(); // response report mask
        buf.readUnsignedShort(); // ign interval
        buf.readUnsignedShort(); // igf interval
        buf.readUnsignedInt(); // reserved
        buf.readUnsignedByte(); // reserved

        if (type == MSG_INF_BAT) {
            position.set(Position.KEY_CHARGE, buf.readUnsignedByte() != 0);
            position.set(Position.KEY_POWER, buf.readUnsignedShort() * 0.001);
            position.set(Position.KEY_BATTERY, buf.readUnsignedShort() * 0.001);
            position.set(Position.KEY_BATTERY_LEVEL, buf.readUnsignedByte());
        }

        buf.skipBytes(10); // iccid

        if (type == MSG_INF_CSQ) {
            position.set(Position.KEY_RSSI, buf.readUnsignedByte());
            buf.readUnsignedByte();
        }

        buf.readUnsignedByte(); // time zone flags
        buf.readUnsignedShort(); // time zone offset

        if (type == MSG_INF_GIR) {
            buf.readUnsignedByte(); // gir trigger
            buf.readUnsignedByte(); // cell number
            position.setNetwork(new Network(CellTower.from(
                    buf.readUnsignedShort(), buf.readUnsignedShort(),
                    buf.readUnsignedShort(), buf.readUnsignedShort())));
            buf.readUnsignedByte(); // ta
            buf.readUnsignedByte(); // rx level
        }

        getLastLocation(position, decodeTime(buf));

        return position;
    }

    public static final int MSG_OBD_OBD = 0;
    public static final int MSG_OBD_OPN = 1;
    public static final int MSG_OBD_OPF = 2;

    private Position decodeObd(Channel channel, SocketAddress remoteAddress, ByteBuf buf) {

        Position position = new Position(getProtocolName());
        position.set("type", "OBD");

        int type = buf.readUnsignedByte();

        boolean[] maskBite = toBinary(buf.readUnsignedInt(), 8);

        if (maskBite[maskBite.length - 1]) {
            buf.readUnsignedShort(); // length
        }
        if (maskBite[maskBite.length - 3]) {
            buf.readUnsignedByte(); // device type
        }
        if (maskBite[maskBite.length - 4]) {
            buf.readUnsignedShort(); // protocol version
        }
        if (maskBite[maskBite.length - 5]) {
            buf.readUnsignedShort(); // firmware version
        }

        DeviceSession deviceSession = getDeviceSession(channel, remoteAddress, decodeDeviceId(buf));
        if (deviceSession == null) {
            return null;
        }
        if (maskBite[maskBite.length - 8])
            buf.skipBytes(17); // vin num

        int reportType = buf.readUnsignedByte();

        boolean[] obdMask = toBinary(buf.readUnsignedInt(), 32);

        position.setDeviceId(deviceSession.getDeviceId());

        if (obdMask[obdMask.length - 1])
            buf.skipBytes(17); // vin num
        if (obdMask[obdMask.length - 2])
            position.set("obdConnection", buf.readUnsignedByte());
        if (obdMask[obdMask.length - 3])
            position.set(Position.KEY_POWER, readValue(buf, 2, false) * 0.001);
        if (obdMask[obdMask.length - 4])
            position.set("supportPIDs", buf.readUnsignedInt());
        if (obdMask[obdMask.length - 5])
            position.set(Position.KEY_RPM, buf.readUnsignedShort());
        if (obdMask[obdMask.length - 6])
            position.set(Position.KEY_OBD_SPEED, buf.readUnsignedShort());
        if (obdMask[obdMask.length - 7])
            position.set(Position.KEY_COOLANT_TEMP, buf.readShort());
        if (obdMask[obdMask.length - 8])
            position.set(Position.KEY_FUEL_CONSUMPTION, buf.readUnsignedShort());
        if (obdMask[obdMask.length - 9])
            position.set(Position.KEY_DTCS, buf.readUnsignedShort());
        if (obdMask[obdMask.length - 10])
            position.set("milDistance", buf.readUnsignedShort());
        if (obdMask[obdMask.length - 11])
            position.set("milStatus", buf.readUnsignedByte());
        if (obdMask[obdMask.length - 12]) {
            int dtsNumbers = buf.readUnsignedByte();
            for (int i = 0; i < dtsNumbers; i++) {
                position.set("dts" + i, buf.readUnsignedShort());
            }
            if (dtsNumbers == 0)
                buf.readUnsignedShort();
        }
        if (obdMask[obdMask.length - 14])
            position.set(Position.KEY_THROTTLE, buf.readUnsignedByte());
        if (obdMask[obdMask.length - 15])
            position.set(Position.KEY_ENGINE_LOAD, buf.readUnsignedByte());
        if (obdMask[obdMask.length - 16])
            position.set(Position.KEY_FUEL_LEVEL, buf.readUnsignedByte());
        if (obdMask[obdMask.length - 20])
            position.set("other", buf.readUnsignedShort());
        if (obdMask[obdMask.length - 21])
            decodePosition(position, buf);
        if (obdMask[obdMask.length - 22])
            decodeNetwork(position, buf);
        if (obdMask[obdMask.length - 23])
            position.set(Position.KEY_TOTAL_DISTANCE, buf.readUnsignedInt());

        return position;
    }

    private static boolean[] toBinary(long number, int base) {
        final boolean[] ret = new boolean[base];
        for (int i = 0; i < base; i++) {
            ret[base - 1 - i] = (1 << i & number) != 0;
        }
        return ret;
    }

    @Override
    protected Object decode(
            Channel channel, SocketAddress remoteAddress, Object msg) throws Exception {

        ByteBuf buf = (ByteBuf) msg;

        switch (buf.readSlice(4).toString(StandardCharsets.US_ASCII)) {
            case "+RSP":
                return decodeLocation(channel, remoteAddress, buf);
            case "+INF":
                return decodeInformation(channel, remoteAddress, buf);
            case "+EVT":
                return decodeEvent(channel, remoteAddress, buf);
            case "+OBD":
                return decodeObd(channel, remoteAddress, buf);
            default:
                return null;
        }
    }

}
