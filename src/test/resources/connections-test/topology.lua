cartridge = require('cartridge')
replicasets = { {
                    alias = 'app-router-first',
                    roles = { 'vshard-router', 'app.roles.custom' },
                    join_servers = { { uri = 'localhost:3301' } }
                }, {
                    alias = 'app-router-second',
                    roles = { 'vshard-router', 'app.roles.custom' },
                    join_servers = { { uri = 'localhost:3302' } }
                }, {
                    alias = 'app-router-third',
                    roles = { 'vshard-router', 'app.roles.custom' },
                    join_servers = { { uri = 'localhost:3303' } }
                }, {
                    alias = 's1-storage',
                    roles = { 'vshard-storage' },
                    join_servers = { { uri = 'localhost:3312' }, { uri = 'localhost:3313' } }
                }, {
                    alias = 's2-storage',
                    roles = { 'vshard-storage' },
                    join_servers = { { uri = 'localhost:3314' }, { uri = 'localhost:3315' } }
                } }
return cartridge.admin_edit_topology({ replicasets = replicasets })
