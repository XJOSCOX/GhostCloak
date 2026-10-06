package org.ghostcloak.messaging

import kotlinx.serialization.Serializable
import java.nio.ByteBuffer
import java.security.SecureRandom
import org.ghostcloak.identity.RandomIdentifiers

enum class DisappearingTimer(val seconds: Int, val label: String) {
    OFF(0, "Off"), SECONDS_30(30, "30 seconds"), MINUTES_5(300, "5 minutes"),
    HOUR(3600, "1 hour"), DAY(86400, "1 day"), WEEK(604800, "1 week");
    companion object { fun from(seconds: Int) = entries.singleOrNull { it.seconds == seconds }
        ?: throw AppFailure(AppError.INVALID_TEXT) }
}

data class ReactionUpdate(val targetMessageId:String,val sequence:Long,val emoji:String?)
data class EditUpdate(val targetMessageId:String,val revision:Long,val text:String)

/** Application framing only: these bytes go inside the existing authenticated Signal envelope. */
object ConversationPayload {
    // Authenticated padding extension. Older decoders already ignore padding.
    // Not user text: a quoted/copied advertisement can never establish support.
    private val attachmentSupport = "GhostCloak/padding/attachments/v1!".toByteArray(Charsets.US_ASCII)
    private val mediaSupport = "GhostCloak/padding/media/v1!".toByteArray(Charsets.US_ASCII)
    private val reactionSupport = "GhostCloak/padding/reactions/v1!".toByteArray(Charsets.US_ASCII)
    private val profileSupport = "GhostCloak/padding/profile/v2!".toByteArray(Charsets.US_ASCII)
    private val deleteSupport = "GhostCloak/padding/delete/v1!".toByteArray(Charsets.US_ASCII)
    private val editSupport = "GhostCloak/padding/edit/v1!".toByteArray(Charsets.US_ASCII)
    private val groupSupport = "GhostCloak/padding/group-membership/v1!".toByteArray(Charsets.US_ASCII)
    // The original marker often did not fit after the legacy profile name and other flags.
    // This authenticated, fixed-position marker fits the existing 256-byte frame.
    private val compactGroupSupport = "GhostCloak/grp2!".toByteArray(Charsets.US_ASCII)
    private val admissionV2Support = "GC/admission-v2!".toByteArray(Charsets.US_ASCII)
    private val baselineV1Support = "GC/baseline-v1!".toByteArray(Charsets.US_ASCII)
    val reactionEmoji = listOf("👍", "❤️", "😂", "😮", "😢", "🙏")
    private val profileMarker = "GhostCloak/profile/v1!".toByteArray(Charsets.US_ASCII)
    const val MAX_TEXT = 16_368 // 16-byte header within the existing 16KiB encrypted-content limit.
    const val MAX_CAPTION_BYTES = 512
    private val magic = byteArrayOf(-1, 71, 67, 80)
    class Content(val body: String, val seconds: Int, val control: Boolean, val attachment: ByteArray? = null,
        val supportsAttachments: Boolean = false, val displayName: String? = null,
        val profileUpdate: Boolean = false, val viewOnceKind: ViewOnceKind? = null,
        val replyTo: ReplyReference? = null, val supportsMedia:Boolean=false,
        val supportsReactions:Boolean=false,val reaction:ReactionUpdate?=null,
        val supportsProfiles:Boolean=false,val profile:ProfileUpdate?=null,
        val supportsDelete:Boolean=false,val deleteTargetId:String?=null,
        val supportsEdit:Boolean=false,val edit:EditUpdate?=null,
        val supportsGroups:Boolean=false,val supportsAdmissionV2:Boolean=false,
        val supportsBaselineV1:Boolean=false,
        val groupControl:GroupControl?=null,
        val groupText:GroupText?=null) {
        override fun toString() = "Content(redacted)"
    }
    private fun profileBytes(displayName:String?):ByteArray = displayName?.let {
        TextRules.displayName(it)
        val name=it.encodeToByteArray()
        profileMarker + byteArrayOf(name.size.toByte()) + name
    } ?: byteArrayOf()
    /** Type 4 is a standalone, versioned profile update inside the Signal envelope. */
    fun encodeProfile(displayName:String):ByteArray {
        val profile=profileBytes(displayName)
        val bytes=ByteArray(256).also { SecureRandom().nextBytes(it) }
        ByteBuffer.wrap(bytes).put(magic).put(1).put(4).putShort(0).putInt(0).putInt(0)
        profile.copyInto(bytes,16)
        return bytes
    }
    /** Type 10 is sent only after an authenticated peer advertisement. */
    fun encodeProfile(update:ProfileUpdate):ByteArray {
        require(update.revision>0)
        val name=if(update.sharing) update.displayName else ""
        if(update.sharing) TextRules.displayName(name)
        val about=if(update.sharing) ProfileRules.about(update.about) else ""
        require(about==update.about || !update.sharing)
        val photo=if(update.sharing) update.photo else null
        photo?.let(ProfileRules::photo)
        val nameBytes=name.encodeToByteArray(); val aboutBytes=about.encodeToByteArray()
        val length=8+1+1+2+2+nameBytes.size+aboutBytes.size+(photo?.size ?: 0)
        val size=((16+length+255)/256)*256
        require(size<=ProfileRules.MAX_PADDED_UPDATE)
        return ByteArray(size).also { bytes ->
            SecureRandom().nextBytes(bytes)
            ByteBuffer.wrap(bytes).put(magic).put(1).put(10).putShort(0).putInt(0).putInt(length)
                .putLong(update.revision).put(if(update.sharing) 1.toByte() else 0.toByte())
                .put(nameBytes.size.toByte()).putShort(aboutBytes.size.toShort()).putShort((photo?.size ?: 0).toShort())
                .put(nameBytes).put(aboutBytes)
            if(photo!=null) ByteBuffer.wrap(bytes,16+length-photo.size,photo.size).put(photo)
        }
    }
    fun validateCaption(value:String):String {
        val trimmed=value.trim()
        if(trimmed.isNotEmpty()) {
            val bytes=trimmed.encodeToByteArray()
            if(bytes.size>MAX_CAPTION_BYTES || bytes.decodeToString()!=trimmed ||
                trimmed.any { Character.isISOControl(it) }) throw AppFailure(AppError.INVALID_TEXT)
        }
        return trimmed
    }
    /** Type 9 is an authenticated Signal control; no plaintext reaction reaches the transport. */
    fun encodeReaction(update:ReactionUpdate):ByteArray {
        require(RandomIdentifiers.valid(update.targetMessageId) && update.sequence>0)
        val emoji=if(update.emoji==null) 0 else reactionEmoji.indexOf(update.emoji)+1
        require(emoji in 0..reactionEmoji.size && (update.emoji==null || emoji>0))
        val bytes=ByteArray(256).also { SecureRandom().nextBytes(it) }
        ByteBuffer.wrap(bytes).put(magic).put(1).put(9).putShort(0).putInt(0).putInt(46)
            .put(update.targetMessageId.toByteArray(Charsets.US_ASCII)).putLong(update.sequence)
            .put(emoji.toByte()).put(0)
        return bytes
    }
    /** Type 11 is an authenticated delete request, gated by the peer's padding advertisement. */
    fun encodeDelete(targetMessageId:String):ByteArray {
        require(RandomIdentifiers.valid(targetMessageId))
        return ByteArray(256).also { bytes ->
            SecureRandom().nextBytes(bytes)
            ByteBuffer.wrap(bytes).put(magic).put(1).put(11).putShort(0).putInt(0).putInt(36)
                .put(targetMessageId.toByteArray(Charsets.US_ASCII))
        }
    }
    /** Type 12 carries one authenticated revision. No old text or edit history is serialized. */
    fun encodeEdit(update:EditUpdate):ByteArray {
        require(RandomIdentifiers.valid(update.targetMessageId) && update.revision>0)
        val text=TextRules.encode(update.text)
        try {
            val length=44+text.size
            val size=((16+length+255)/256)*256
            if(size>16384) throw AppFailure(AppError.MESSAGE_TOO_LARGE)
            return ByteArray(size).also { bytes ->
                SecureRandom().nextBytes(bytes)
                ByteBuffer.wrap(bytes).put(magic).put(1).put(12).putShort(0).putInt(0).putInt(length)
                    .put(update.targetMessageId.toByteArray(Charsets.US_ASCII)).putLong(update.revision).put(text)
            }
        } finally {text.fill(0)}
    }
    /** Type 13 is sent only to peers that advertised authenticated GROUP_MEMBERSHIP_V1 support. */
    fun encodeGroup(control:GroupControl):ByteArray {
        val body=GroupControlCodec.encode(control)
        val size=((16+body.size+255)/256)*256
        require(size<=16384)
        return ByteArray(size).also { bytes ->
            SecureRandom().nextBytes(bytes)
            ByteBuffer.wrap(bytes).put(magic).put(1).put(13).putShort(0).putInt(0).putInt(body.size).put(body)
            if(size-16-body.size>=baselineV1Support.size)
                baselineV1Support.copyInto(bytes,16+body.size)
        }
    }
    /** Type 14 is a group text inside a recipient-specific Signal envelope. */
    fun encodeGroupText(value:GroupText):ByteArray {
        val body=GroupTextCodec.encode(value)
        try {
            val size=((16+body.size+255)/256)*256
            require(size<=4096)
            return ByteArray(size).also { bytes ->
                SecureRandom().nextBytes(bytes)
                ByteBuffer.wrap(bytes).put(magic).put(1).put(14).putShort(0).putInt(0).putInt(body.size).put(body)
            }
        } finally {body.fill(0)}
    }
    fun encodeAttachment(descriptor: org.ghostcloak.attachments.AttachmentDescriptor, displayName:String?=null,
        viewOnce:Boolean=false, caption:String=""): ByteArray {
        if(viewOnce) require(descriptor.kind==org.ghostcloak.attachments.AttachmentKind.IMAGE && caption.isBlank())
        val normalized=validateCaption(caption)
        val captionBytes=normalized.encodeToByteArray()
        val modern=descriptor.kind==org.ghostcloak.attachments.AttachmentKind.VOICE_NOTE || captionBytes.isNotEmpty()
        val encoded = org.ghostcloak.attachments.AttachmentFormat.encode(descriptor)
        try {
            val profile=profileBytes(displayName)
            val size=((16+encoded.size+(if(modern) 2+captionBytes.size else 0)+profile.size+255)/256)*256
            if(size>16384) throw AppFailure(AppError.MESSAGE_TOO_LARGE)
            val bytes=ByteArray(size).also { SecureRandom().nextBytes(it) }
            ByteBuffer.wrap(bytes).put(magic).put(1).put(if(viewOnce) 6 else if(modern) 8 else 3).putShort(0)
                .putInt(descriptor.disappearingSeconds).putInt(encoded.size).put(encoded)
            if(modern) ByteBuffer.wrap(bytes,16+encoded.size,2+captionBytes.size)
                .putShort(captionBytes.size.toShort()).put(captionBytes)
            if(profile.isNotEmpty()) profile.copyInto(bytes,16+encoded.size+(if(modern) 2+captionBytes.size else 0))
            return bytes
        } finally { encoded.fill(0);captionBytes.fill(0) }
    }
    fun encode(body: String, seconds: Int, control: Boolean = false, displayName:String?=null,
        viewOnce:Boolean=false, replyTo:ReplyReference?=null): ByteArray {
        require(!viewOnce || !control)
        require(replyTo==null || (!control && !viewOnce && RandomIdentifiers.valid(replyTo.envelopeId)))
        DisappearingTimer.from(seconds)
        val text = if (control) { require(body.isEmpty()); byteArrayOf() } else TextRules.encode(body)
        try {
            if (text.size > MAX_TEXT) throw AppFailure(AppError.MESSAGE_TOO_LARGE)
            val requestedProfile=profileBytes(displayName)
            val replyBytes=replyTo?.let { byteArrayOf(it.kind.ordinal.toByte()) + it.envelopeId.toByteArray(Charsets.US_ASCII) } ?: byteArrayOf()
            val prefixSize=16+text.size+replyBytes.size
            val extension=if(prefixSize+attachmentSupport.size+requestedProfile.size<=16384)
                attachmentSupport else byteArrayOf()
            fun paddedSize(profileSize:Int)=((prefixSize+extension.size+profileSize+255)/256)*256
            val earlierFlagsSize=mediaSupport.size+reactionSupport.size+profileSupport.size+
                deleteSupport.size+editSupport.size
            fun groupSpace(profileSize:Int)=paddedSize(profileSize)-
                (prefixSize+extension.size+profileSize+earlierFlagsSize)
            // Preserve the legacy display-name extension whenever the compact marker fits.
            // A very long name is already delivered by the separate encrypted profile update;
            // omit its redundant copy here only if that lets the full group marker fit.
            val profile=if(extension.isNotEmpty() && requestedProfile.isNotEmpty() &&
                groupSpace(requestedProfile.size)<compactGroupSupport.size &&
                groupSpace(0)>=groupSupport.size) byteArrayOf() else requestedProfile
            val size = paddedSize(profile.size)
            if(size>16384) throw AppFailure(AppError.MESSAGE_TOO_LARGE)
            // Keep the exact legacy padded size. Older clients ignore this marker inside padding.
            val media=extension.isNotEmpty() && 16+text.size+replyBytes.size+extension.size+profile.size+mediaSupport.size<=size
            val bytes = ByteArray(size).also { SecureRandom().nextBytes(it) }
            ByteBuffer.wrap(bytes).put(magic).put(1).put(if (control) 2 else if(viewOnce) 5 else if(replyTo!=null) 7 else 1).putShort(0)
                .putInt(seconds).putInt(text.size).put(text).put(replyBytes)
            if (extension.isNotEmpty()) extension.copyInto(bytes, 16 + text.size+replyBytes.size)
            if(profile.isNotEmpty()) profile.copyInto(bytes,16+text.size+replyBytes.size+extension.size)
            if(media) mediaSupport.copyInto(bytes,16+text.size+replyBytes.size+extension.size+profile.size)
            val reactionOffset=16+text.size+replyBytes.size+extension.size+profile.size+(if(media) mediaSupport.size else 0)
            if(extension.isNotEmpty() && reactionOffset+reactionSupport.size<=size)
                reactionSupport.copyInto(bytes,reactionOffset)
            val profileOffset=reactionOffset+(if(extension.isNotEmpty() && reactionOffset+reactionSupport.size<=size) reactionSupport.size else 0)
            if(extension.isNotEmpty() && profileOffset+profileSupport.size<=size)
                profileSupport.copyInto(bytes,profileOffset)
            val deleteOffset=profileOffset+(if(extension.isNotEmpty() && profileOffset+profileSupport.size<=size) profileSupport.size else 0)
            if(extension.isNotEmpty() && deleteOffset+deleteSupport.size<=size)
                deleteSupport.copyInto(bytes,deleteOffset)
            val editOffset=deleteOffset+(if(extension.isNotEmpty() && deleteOffset+deleteSupport.size<=size) deleteSupport.size else 0)
            if(extension.isNotEmpty() && editOffset+editSupport.size<=size)
                editSupport.copyInto(bytes,editOffset)
            val groupOffset=editOffset+(if(extension.isNotEmpty() && editOffset+editSupport.size<=size) editSupport.size else 0)
            val groupSize=if(extension.isNotEmpty()) when {
                groupOffset+groupSupport.size<=size -> groupSupport.also {it.copyInto(bytes,groupOffset)}.size
                groupOffset+compactGroupSupport.size<=size -> compactGroupSupport.also {it.copyInto(bytes,groupOffset)}.size
                else -> 0
            } else 0
            if(groupSize>0 && groupOffset+groupSize+admissionV2Support.size<=size)
                admissionV2Support.copyInto(bytes,groupOffset+groupSize)
            return bytes
        } finally { text.fill(0) }
    }
    fun decode(bytes: ByteArray): Content = try { decodeChecked(bytes) }
        catch (_: java.nio.charset.CharacterCodingException) { throw AppFailure(AppError.INVALID_TEXT) }
    /** Classify only the authenticated outer type. Never decode blocked user content. */
    fun decodeBlockedGroupSystem(bytes: ByteArray): GroupControl? {
        return decodeBlockedGroupSystemContent(bytes)?.groupControl
    }
    /** Retain authenticated maintenance capability without inspecting blocked user content. */
    fun decodeBlockedGroupSystemContent(bytes: ByteArray): Content? {
        if (bytes.size < 6 || !bytes.copyOfRange(0,4).contentEquals(magic) || bytes[5].toInt()!=13)
            return null
        return decode(bytes).also {if(it.groupControl==null) throw AppFailure(AppError.INVALID_TEXT)}
    }
    private fun decodeChecked(bytes: ByteArray): Content {
        if (bytes.isEmpty()) throw AppFailure(AppError.INVALID_TEXT)
        // Old UTF-8 messages are still accepted as ordinary, non-expiring text.
        if (bytes[0] != magic[0]) {
            val body = bytes.decodeToString(throwOnInvalidSequence = true)
            TextRules.encode(body).fill(0); return Content(body, 0, false)
        }
        if (bytes.size !in 256..16384 || bytes.size % 256 != 0) throw AppFailure(AppError.INVALID_TEXT)
        val input = ByteBuffer.wrap(bytes)
        if (!ByteArray(4).also { input.get(it) }.contentEquals(magic) || input.get().toInt() != 1)
            throw AppFailure(AppError.INVALID_TEXT)
        val type = input.get().toInt()
        if (type !in 1..14 || input.short.toInt() != 0) throw AppFailure(AppError.INVALID_TEXT)
        val seconds = input.int; DisappearingTimer.from(seconds)
        val length = input.int
        if (length !in 0..MAX_TEXT || length > input.remaining() || (type in setOf(2,4) && length != 0) ||
            (type == 4 && (seconds != 0 || bytes.size != 256))) throw AppFailure(AppError.INVALID_TEXT)
        val text = ByteArray(length).also { input.get(it) }
        if(type==10) {
            try {
                if(seconds!=0 || bytes.size>ProfileRules.MAX_PADDED_UPDATE || length<14 ||
                    ((16+length+255)/256)*256!=bytes.size) throw AppFailure(AppError.INVALID_TEXT)
                val value=ByteBuffer.wrap(text)
                val revision=value.long;val sharing=value.get().toInt();val nameLength=value.get().toInt() and 255
                val aboutLength=value.short.toInt() and 65535;val photoLength=value.short.toInt() and 65535
                if(revision<=0 || sharing !in 0..1 || nameLength+aboutLength+photoLength!=value.remaining())
                    throw AppFailure(AppError.INVALID_TEXT)
                val name=ByteArray(nameLength).also(value::get).decodeToString(throwOnInvalidSequence=true)
                val about=ByteArray(aboutLength).also(value::get).decodeToString(throwOnInvalidSequence=true)
                val photo=if(photoLength>0) ByteArray(photoLength).also(value::get) else null
                if(sharing==1) TextRules.displayName(name)
                else if(name.isNotEmpty() || about.isNotEmpty() || photo!=null) throw AppFailure(AppError.INVALID_TEXT)
                if(ProfileRules.about(about)!=about) throw AppFailure(AppError.INVALID_TEXT)
                photo?.let(ProfileRules::photo)
                return Content("",0,false,profileUpdate=true,profile=ProfileUpdate(revision,name,about,photo,sharing==1),
                    supportsProfiles=true)
            } catch (_:IllegalArgumentException) {throw AppFailure(AppError.INVALID_TEXT)}
            finally {text.fill(0)}
        }
        if(type==9) {
            if(length!=46 || seconds!=0 || bytes.size!=256) throw AppFailure(AppError.INVALID_TEXT)
            val value=ByteBuffer.wrap(text)
            val target=ByteArray(36).also(value::get).toString(Charsets.US_ASCII)
            val sequence=value.long
            val emoji=value.get().toInt();val reserved=value.get().toInt()
            text.fill(0)
            if(!RandomIdentifiers.valid(target) || sequence<=0 || emoji !in 0..reactionEmoji.size || reserved!=0)
                throw AppFailure(AppError.INVALID_TEXT)
            return Content("",0,true,reaction=ReactionUpdate(target,sequence,
                if(emoji==0) null else reactionEmoji[emoji-1]))
        }
        if(type==11) {
            if(length!=36 || seconds!=0 || bytes.size!=256) throw AppFailure(AppError.INVALID_TEXT)
            val target=text.toString(Charsets.US_ASCII);text.fill(0)
            if(!RandomIdentifiers.valid(target)) throw AppFailure(AppError.INVALID_TEXT)
            return Content("",0,true,deleteTargetId=target)
        }
        if(type==12) {
            if(length<=44 || seconds!=0 || ((16+length+255)/256)*256!=bytes.size)
                throw AppFailure(AppError.INVALID_TEXT)
            val value=ByteBuffer.wrap(text)
            val target=ByteArray(36).also(value::get).toString(Charsets.US_ASCII)
            val revision=value.long
            val body=try {ByteArray(value.remaining()).also(value::get).decodeToString(throwOnInvalidSequence=true)}
                finally {text.fill(0)}
            if(!RandomIdentifiers.valid(target) || revision<=0) throw AppFailure(AppError.INVALID_TEXT)
            TextRules.encode(body).fill(0)
            return Content("",0,true,edit=EditUpdate(target,revision,body))
        }
        if(type==13) {
            if(seconds!=0 || length !in 1..GroupControlCodec.MAX_BYTES ||
                ((16+length+255)/256)*256!=bytes.size) throw AppFailure(AppError.INVALID_TEXT)
            val control=try {GroupControlCodec.decode(text)}
                catch (_:IllegalArgumentException) {throw AppFailure(AppError.INVALID_TEXT)}
                catch (_:org.ghostcloak.protocol.ApiFailure) {throw AppFailure(AppError.INVALID_TEXT)}
                finally {text.fill(0)}
            val marker=bytes.size-16-length>=baselineV1Support.size &&
                bytes.copyOfRange(16+length,16+length+baselineV1Support.size)
                    .contentEquals(baselineV1Support)
            return Content("",0,true,groupControl=control,supportsGroups=true,
                supportsBaselineV1=marker)
        }
        if(type==14) {
            if(seconds!=0 || length !in 1..GroupTextCodec.MAX_BYTES ||
                ((16+length+255)/256)*256!=bytes.size || bytes.size>4096) throw AppFailure(AppError.INVALID_TEXT)
            val value=try {GroupTextCodec.decode(text)}
                catch (_:IllegalArgumentException) {throw AppFailure(AppError.INVALID_TEXT)}
                catch (_:org.ghostcloak.protocol.ApiFailure) {throw AppFailure(AppError.INVALID_TEXT)}
                finally {text.fill(0)}
            return Content("",0,true,groupText=value,supportsGroups=true)
        }
        val reply = if(type==7) {
            if(input.remaining()<37) throw AppFailure(AppError.INVALID_TEXT)
            val kind=ReplyKind.entries.getOrNull(input.get().toInt()) ?: throw AppFailure(AppError.INVALID_TEXT)
            val idBytes=ByteArray(36).also(input::get)
            val id=idBytes.toString(Charsets.US_ASCII)
            if(!RandomIdentifiers.valid(id)) throw AppFailure(AppError.INVALID_TEXT)
            ReplyReference(id,kind)
        } else null
        var offset=16+length+(if(reply!=null) 37 else 0)
        val caption=if(type==8) {
            if(bytes.size-offset<2) throw AppFailure(AppError.INVALID_TEXT)
            val count=ByteBuffer.wrap(bytes,offset,2).short.toInt() and 0xffff
            if(count>MAX_CAPTION_BYTES || bytes.size-offset-2<count) throw AppFailure(AppError.INVALID_TEXT)
            val value=bytes.copyOfRange(offset+2,offset+2+count).decodeToString(throwOnInvalidSequence=true)
            if(value!=validateCaption(value)) throw AppFailure(AppError.INVALID_TEXT)
            offset+=2+count
            value
        } else ""
        val supports = bytes.size-offset>=attachmentSupport.size &&
            bytes.copyOfRange(offset,offset+attachmentSupport.size).contentEquals(attachmentSupport)
        if(supports) offset+=attachmentSupport.size
        val name = if(bytes.size-offset>=profileMarker.size+1 &&
            bytes.copyOfRange(offset,offset+profileMarker.size).contentEquals(profileMarker)) {
            val count=bytes[offset+profileMarker.size].toInt() and 255
            if(count !in 1..128 || bytes.size-offset-profileMarker.size-1<count) throw AppFailure(AppError.INVALID_TEXT)
            val decoded=bytes.copyOfRange(offset+profileMarker.size+1,offset+profileMarker.size+1+count)
                .decodeToString(throwOnInvalidSequence=true)
            TextRules.displayName(decoded)
            decoded
        } else null
        if(name!=null) offset+=profileMarker.size+1+name.encodeToByteArray().size
        val media = supports && bytes.size-offset>=mediaSupport.size &&
            bytes.copyOfRange(offset,offset+mediaSupport.size).contentEquals(mediaSupport)
        if(media) offset+=mediaSupport.size
        val reactions=supports && bytes.size-offset>=reactionSupport.size &&
            bytes.copyOfRange(offset,offset+reactionSupport.size).contentEquals(reactionSupport)
        if(reactions) offset+=reactionSupport.size
        val profiles=supports && bytes.size-offset>=profileSupport.size &&
            bytes.copyOfRange(offset,offset+profileSupport.size).contentEquals(profileSupport)
        if(profiles) offset+=profileSupport.size
        val deletes=supports && bytes.size-offset>=deleteSupport.size &&
            bytes.copyOfRange(offset,offset+deleteSupport.size).contentEquals(deleteSupport)
        if(deletes) offset+=deleteSupport.size
        val edits=supports && bytes.size-offset>=editSupport.size &&
            bytes.copyOfRange(offset,offset+editSupport.size).contentEquals(editSupport)
        if(edits) offset+=editSupport.size
        val fullGroups=supports && bytes.size-offset>=groupSupport.size &&
            bytes.copyOfRange(offset,offset+groupSupport.size).contentEquals(groupSupport)
        val compactGroups=!fullGroups && supports && bytes.size-offset>=compactGroupSupport.size &&
            bytes.copyOfRange(offset,offset+compactGroupSupport.size).contentEquals(compactGroupSupport)
        if(fullGroups) offset+=groupSupport.size
        else if(compactGroups) offset+=compactGroupSupport.size
        val admissionV2=(fullGroups || compactGroups) && bytes.size-offset>=admissionV2Support.size &&
            bytes.copyOfRange(offset,offset+admissionV2Support.size).contentEquals(admissionV2Support)
        if(admissionV2) offset+=admissionV2Support.size
        val minimum=offset
        if(((minimum+255)/256)*256!=bytes.size)
            throw AppFailure(AppError.INVALID_TEXT)
        if(type==4) {
            if(name==null || supports) throw AppFailure(AppError.INVALID_TEXT)
            return Content("",0,false,displayName=name,profileUpdate=true)
        }
        if (type == 3 || type == 6 || type==8) {
            try {
                val descriptor=org.ghostcloak.attachments.AttachmentFormat.decode(text)
                require(descriptor.disappearingSeconds==seconds)
                if(type==6) require(descriptor.kind==org.ghostcloak.attachments.AttachmentKind.IMAGE)
                if(type==8) require(descriptor.kind==org.ghostcloak.attachments.AttachmentKind.VOICE_NOTE || caption.isNotEmpty())
                return Content(caption,seconds,false,text,displayName=name,
                    viewOnceKind=if(type==6) ViewOnceKind.PHOTO else null)
            } catch (_: Exception) { text.fill(0); throw AppFailure(AppError.INVALID_TEXT) }
        }
        val body = try { text.decodeToString(throwOnInvalidSequence = true) } finally { text.fill(0) }
        if (type == 1 || type == 5 || type == 7) TextRules.encode(body).fill(0)
        return Content(body, seconds, type == 2, supportsAttachments = supports,displayName=name,
            viewOnceKind=if(type==5) ViewOnceKind.TEXT else null,replyTo=reply,supportsMedia=media,
            supportsReactions=reactions,supportsProfiles=profiles,supportsDelete=deletes,supportsEdit=edits,
            supportsGroups=fullGroups || compactGroups,supportsAdmissionV2=admissionV2)
    }
    fun policyText(seconds: Int) = if (seconds == 0) "Disappearing messages turned off"
        else "Disappearing messages set to ${DisappearingTimer.from(seconds).label}"
}

data class ExpiryMoment(val wall: Long, val elapsed: Long, val boot: Int)
/** Wall time never moves backwards while this clock instance observes the same boot. */
class ExpiryClock(private val wall: () -> Long = System::currentTimeMillis,
    private val elapsed: () -> Long = { System.nanoTime() / 1_000_000 }, private val boot: () -> Int = { 0 }) {
    private var last: ExpiryMoment? = null
    @Synchronized fun now(): ExpiryMoment {
        val e = elapsed(); val b = boot(); val previous = last
        val w = if (previous != null && previous.boot == b && e >= previous.elapsed)
            maxOf(wall(), previous.wall + e - previous.elapsed) else wall()
        return ExpiryMoment(w, e, b).also { last = it }
    }
}
@Serializable data class ExpiryDeadline(val wall: Long, val elapsed: Long, val boot: Int) {
    fun reached(now: ExpiryMoment) = now.wall >= wall || (now.boot == boot && now.elapsed >= elapsed)
    companion object {
        fun start(seconds: Int, now: ExpiryMoment): ExpiryDeadline {
            DisappearingTimer.from(seconds); require(seconds > 0)
            return ExpiryDeadline(Math.addExact(now.wall, seconds * 1000L), Math.addExact(now.elapsed, seconds * 1000L), now.boot)
        }
    }
}
