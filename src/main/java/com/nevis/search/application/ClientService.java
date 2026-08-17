package com.nevis.search.application;

import com.nevis.search.application.port.ClientRepository;
import com.nevis.search.domain.Client;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class ClientService {

    private final ClientRepository clientRepository;

    public ClientService(ClientRepository clientRepository) {
        this.clientRepository = clientRepository;
    }

    public Client create(String firstName, String lastName, String email, String countryOfResidence) {
        Client client = new Client(
                UUID.randomUUID(),
                firstName.strip(),
                lastName.strip(),
                email.strip(),
                countryOfResidence == null ? null : countryOfResidence.strip()
        );
        return clientRepository.save(client);
    }
}

