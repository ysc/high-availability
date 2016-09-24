package org.apdplat.service.configration;

import java.util.Observer;
import java.util.Vector;

/**
 * Created by ysc on 7/5/16.
 */
public class ConfManager {
    private static final Vector<Observer> OBSERVERS = new Vector<>();

    public static void addObserver(Observer observer){
        if (observer == null)
            throw new NullPointerException();
        if (!OBSERVERS.contains(observer)) {
            OBSERVERS.addElement(observer);
        }
    }

    public static void notifyObservers() {
        OBSERVERS.forEach(observer -> observer.update(null, null));
    }
}
