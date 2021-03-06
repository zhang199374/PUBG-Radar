@file:Suppress("NOTHING_TO_INLINE")

package pubg.radar.deserializer

import pubg.radar.*
import pubg.radar.deserializer.channel.ActorChannel
import pubg.radar.deserializer.channel.Channel.Companion.closedInChannels
import pubg.radar.deserializer.channel.Channel.Companion.closedOutChannels
import pubg.radar.deserializer.channel.Channel.Companion.inChannels
import pubg.radar.deserializer.channel.Channel.Companion.outChannels
import pubg.radar.deserializer.channel.ControlChannel
import pubg.radar.struct.Bunch

fun Int.d(w: Int): String {
  return String.format("%${w}d", this)
}

fun proc_raw_packet(raw: ByteArray, client: Boolean = true) {
  val reader = Buffer(raw)
  reader.proc_raw_packet(client)
}

fun Buffer.proc_raw_packet(client: Boolean) {
  if (readBit()) {
    return
  }
  if (readBit()) {
    return
  }
  val packetId = readInt(MAX_PACKETID)
  while (notEnd()) {
    val IsAck = readBit()
    if (IsAck) {
      val ackPacketId = readInt()
      if (ackPacketId == -1) return
      val bHasServerFrameTime = readBit()
      val remoteInKBytesPerSecond = readIntPacked()
      continue
    }
    val bControl = readBit()
    var bOpen = false
    var bClose = false
    var bDormant = false
    if (bControl) {
      bOpen = readBit()
      bClose = readBit()
      if (bClose) bDormant = readBit()
    }
    val bIsReplicationPaused = readBit()
    val bReliable = readBit()
    val chIndex = readInt(MAX_CHANNELS)
    val bHasPackageMapExports = readBit()
    val bHasMustBeMappedGUIDs = readBit()
    val bPartial = readBit()
    val chSequence = when {
      bReliable -> readInt(MAX_CHSEQUENCE)
      bPartial -> packetId
      else -> 0
    }
    var bPartialInitial = false
    var bPartialFinal = false
    if (bPartial) {
      bPartialInitial = readBit()
      bPartialFinal = readBit()
    }

    val chType = if (bReliable || bOpen) readInt(CHTYPE_MAX) else CHTYPE_NONE
    check(chType <= 4)
    val bunchDataBits = readInt(MAX_PACKET_SIZE * 8)
    val pre = bitsLeft()
    if (bunchDataBits > pre) {
      return
    }

    val channels = if (client) inChannels else outChannels
    val closedChannels = if (client) closedInChannels else closedOutChannels
    if (chIndex !in channels && (chIndex != 0 || chType != CHTYPE_CONTROL))
    // Can't handle other channels until control channel exists.
      if (client && channels[0] == null) {
        return
      }

    // ignore control channel close if it hasn't been opened yet
    if (chIndex == 0 && channels[0] == null && bClose && chType == CHTYPE_CONTROL) {
      return
    }

    if (chIndex !in channels && !bReliable) {
      //Unreliable bunches that open channels should be bOpen && (bClose || bPartial)
      val validUnreliableOpen = bOpen && (bClose || bPartial)
      if (!validUnreliableOpen) {
        skipBits(bunchDataBits)
        continue
      }
    }

    if (chIndex !in channels) {
      when (chType) {
        CHTYPE_CONTROL -> {
          channels[chIndex] = ControlChannel(chIndex, client)
        }
        CHTYPE_VOICE, CHTYPE_FILE -> {

        }
        else -> {
          if (chType == CHTYPE_NONE)
            println("$chSequence lost the first actor creation bunch. just create as we need it.")
          inChannels[chIndex] = ActorChannel(chIndex, true)
          outChannels[chIndex] = ActorChannel(chIndex, false)
        }
      }
    }
    val chan = channels[chIndex]

    if (chan != null) {
      check(chType == CHTYPE_NONE || chType == chan.chType)
      try {
        val bunch = Bunch(
            bunchDataBits,
            deepCopy(bunchDataBits),
            packetId,
            chIndex,
            chType,
            chSequence,
            bOpen,
            bClose,
            bDormant,
            bIsReplicationPaused,
            bReliable,
            bPartial,
            bPartialInitial,
            bPartialFinal,
            bHasPackageMapExports, bHasMustBeMappedGUIDs
        )
        chan.ReceivedRawBunch(bunch)
      } catch (e: Exception) {
      }
    }
    skipBits(bunchDataBits)
    check(bitsLeft() + bunchDataBits == pre)
  }
  return
}
