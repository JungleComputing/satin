/* $Id$ */

package ibis.satin.impl.sharedObjects;

import java.io.IOException;

import ibis.ipl.MessageUpcall;
import ibis.ipl.ReadMessage;
import ibis.ipl.util.messagecombining.MessageSplitter;
import ibis.satin.SharedObject;
import ibis.satin.impl.Config;
import ibis.satin.impl.Satin;
import ibis.satin.impl.communication.Protocol;
import ibis.util.Timer;

final class SOInvocationHandler implements MessageUpcall, Config, Protocol {
    private Satin s;

    private MessageSplitter messageSplitter;

    protected SOInvocationHandler(Satin s) {
        this.s = s;
    }

    protected void setMessageSplitter(MessageSplitter ms) {
        messageSplitter = ms;
    }

    private void handleMessage(ReadMessage m) throws IOException {
        SharedObject obj = null;
        SOInvocationRecord soir = null;

        byte opt_code;
        try {
            opt_code = m.readByte();
        } catch (IOException e) {
            System.err.println("SATIN '" + s.ident + "': got exception in SOInvocationHandler: " + e.getMessage());
            e.printStackTrace();
            throw e;
        }

        switch (opt_code) {
        case SO_TRANSFER: // exportObject
            if (soLogger.isInfoEnabled()) {
                soLogger.info("got SO_TRANSFER");
            }
            Timer tmp = Timer.createTimer();
            tmp.start();
            try {
                obj = (SharedObject) m.readObject();
            } catch (IOException e) {
                System.err.println("SATIN '" + s.ident + "': got exception in SOInvocationHandler: " + e.getMessage());
                e.printStackTrace();
                throw e;
            } catch (ClassNotFoundException e) {
                System.err.println("SATIN '" + s.ident + "': got exception in SOInvocationHandler: " + e.getMessage());
                e.printStackTrace();
                return;
            } finally {
                tmp.stop();
                s.stats.soBroadcastDeserializationTimer.add(tmp);
            }

            // no need to finish the message
            s.so.addObject(obj);
            break;
        case SO_INVOCATION: // normal invocation, can be message combined
            if (soLogger.isInfoEnabled()) {
                soLogger.info("got SO_INVOCATION");
            }
            tmp = Timer.createTimer();
            tmp.start();
            try {
                soir = (SOInvocationRecord) m.readObject();
            } catch (IOException e) {
                System.err.println("SATIN '" + s.ident + "': got exception in SOInvocationHandler: " + e.getMessage());
                e.printStackTrace();
                throw e;
            } catch (ClassNotFoundException e) {
                System.err.println("SATIN '" + s.ident + "': got exception in SOInvocationHandler: " + e.getMessage());
                e.printStackTrace();
                return;
            } finally {
                tmp.stop();
                s.stats.soInvocationDeserializationTimer.add(tmp);
            }

            // no need to finish here
            s.so.addSOInvocation(soir);
            break;
        default:
            System.err.println("SATIN '" + s.ident + "': invalid opcode in SOInvocationHandler: " + opt_code);
        }
    }

    // There is a receiveport per sender, and they share this upcall handler.
    // Therefore, upcalls can run concurrently.
    @Override
    public void upcall(ReadMessage m) throws IOException {
        try {
            if (SO_MAX_INVOCATION_DELAY > 0) { // message combining enabled
                messageSplitter.setMessageToSplit(m);
                ReadMessage m1 = messageSplitter.receive();
                while (m1 != null) {
                    handleMessage(m1);
                    // message combining needs all messages to be finished
                    m1.finish();
                    m1 = messageSplitter.receive();
                }
            } else {
                handleMessage(m);
            }
        } catch (IOException e) {
            System.err.println("SATIN '" + s.ident + "': got exception while reading" + " opcode in SOInvocationHandler: " + e.getMessage());
            throw e;
        }
    }
}
